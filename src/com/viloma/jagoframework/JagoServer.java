package com.viloma.jagoframework;

import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.logging.*;
import java.util.regex.*;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.xml.bind.*;
import javax.xml.transform.stream.StreamSource;

public class JagoServer extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(JagoServer.class.getName());
    private static Pattern keyPattern = Pattern.compile(".*/:([a-zA-Z0-9]+)/?.*");

    public static String f(String fileName){return "{{>"+fileName+"}}";}
     @SuppressWarnings("deprecation")
    public static String formatTime(Date dt){
         return String.format("%02d:%02d:%02d", dt.getHours(), dt.getMinutes(), dt.getSeconds());
     }
    public static void print(Level level, String message){
        print(level, message, null);
    }
    public static void print(Level level, String message, Throwable t){
        if(instance != null){
            instance.doLog(level, message, t);
            if(instance.logWriter != null)
                instance.printInternal(level, message, t);
        }
        else
            logger.log(level, message, t);
    }
    public JagoServer(){
        instance = this;
        info("loaded at "+new Date().toString());
    }
    private static JagoServer instance;

    public void doLog(Level level, String message, Throwable t){
        logger.log(level, message, t);
    }
    private static void logthis(Level level, String message, Throwable t){
        if(instance != null)
            instance.doLog(level, message, t);
        else
            logger.log(level, message, t);
    }
    public static void info(String message){logthis(Level.INFO, message, null);}
    public static void debug(String message){logthis(Level.FINE, message, null);}
    public static void warn(String message, Throwable ex){logthis(Level.WARNING, message, ex);}

    private PrintWriter logWriter;
    private StringWriter buffer;
    public void enableLogView(String tag){
        logWriter = new PrintWriter(buffer = new StringWriter());
        addController(GET, tag, null, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                return new TemplResponse(null, buffer.getBuffer().toString());
            }
        });
    }
    public void printInternal(Level level, String message, Throwable t){
        if(logWriter != null){
            logWriter.append(formatTime(new Date())).append(' ').append(level.getName()).append(" ").append(message).append("<br>");
            if(t!=null)
                t.printStackTrace(logWriter);
            if(buffer.getBuffer().length()> 8000)
                buffer.getBuffer().replace(0, 3000, "");
        }
    }

    public static class Db {
        String connString, user, pwd, driver;
        boolean _autoCreateTables=false;
        public void autoCreateTables(){_autoCreateTables=true;}
        Map<String, TableDef> tableDef = new HashMap<String, TableDef>();
        private Connection conn;

        public class ColumnDef {
            public ColumnDef(String fieldName){this.columnName = fieldName;this.fieldName = fieldName;}
            public ColumnDef dbcolumn(String colName){this.columnName = colName; return this;}
            public ColumnDef nullable(boolean nullable){allowNull = nullable; return this;}
            public ColumnDef type(int dataType){this.dataType = dataType; return this;}
            public ColumnDef ignore(){this.ignore = true; return this;}
            private String columnName, fieldName;
            private boolean allowNull = true, ignore = false;
            private Integer dataType;
            private boolean isAutoIncrement = false;
            private Method getMethod, setMethod;
            private Object[] enumVals;
            private Field field;
            private Class<?> fieldType;
        }
        public ColumnDef colDef(String fieldName){return new ColumnDef(fieldName);}
        public Db(String connStr, String driver, String user, String pwd) {
            init();
            this.connString = connStr;
            if(driver != null){
                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException e) {
                    warn("Exception",e);
                }
            }
            this.driver = driver;
            this.user = user;
            this.pwd = pwd;
        }

        public boolean isConnectionValid() throws SQLException {
            return conn != null && conn.isValid(1) && !conn.isClosed();
        }

        private Connection getConn() throws SQLException {
            if (!isConnectionValid()) {
                print(Level.INFO, "ReConnecting to DB");
                conn = DriverManager.getConnection(connString, user, pwd);
            }
            return conn;
        }

        public Map<String,Object> readOne(String query, Object... data) throws SQLException{
            List<? extends Map<String, Object>> res = readListLimit(MyMap.class, query, 1, data);
            if(res != null && !res.isEmpty())
                return res.get(0);
            return null;
        }
        public <T> T readOne(Class<T> clazz, String query, Object... data) throws SQLException{
            List<T> res = readList(clazz, query, data);
            if(res != null && !res.isEmpty())
                return res.get(0);
            return null;
        }
        public List<? extends Map<String, Object>> readListLimit(String query, int maxRows, Object... data) throws SQLException {
            return readListLimit(MyMap.class, query, maxRows, data);
        }
        public boolean executeUpdate(String query, Object... data) throws SQLException {
            int res = executeUpdate(query, false, data);
            return (res > 0);
        }
        private int executeUpdate(String query, boolean getAutoKey, Object... data) throws SQLException {
            PreparedStatement stmt = null;
            try
            {
                print(Level.FINE, "running query :"+query+" with autokey:"+getAutoKey+" pathParams:"+data);
                stmt = getConn().prepareStatement(query, (getAutoKey ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS));
                for (int i = 0; i < data.length; i++) {
                    setValueInStatement(stmt, i+1, data[i]);
                }
                int rowCount = stmt.executeUpdate();
                print(Level.INFO,  "Executed updates "+rowCount);
                if(rowCount == 0 || !getAutoKey)
                    return rowCount;
                else if(getAutoKey){
                    ResultSet keys = null;
                    try {
                        keys = stmt.getGeneratedKeys();
                        if(keys.next())
                        {
                            String colName = keys.getMetaData().getColumnName(1);
                            Object keyVal = keys.getObject(colName);
                            if(keyVal instanceof Integer){
                                print(Level.INFO, "inserted :"+keyVal);
                                return (Integer)keyVal;
                            }
                        }
                    }finally{
                        if(keys!=null)
                            keys.close();
                    }
                    return 0;
                }
                return rowCount;
            }
            catch(SQLException ex){
                throw ex;
            }
            catch(Exception ex){
                print(Level.WARNING, "Exception in qry: "+query, ex);
                throw new SQLException("Exception in qry: "+query+" "+ex.getMessage());
            }
            finally{
                if(stmt != null)
                    stmt.close();
            }
        }
        class TableDef extends TreeMap<String, ColumnDef>{
            private static final long serialVersionUID = 1L;
            String tableName, insertQuery, updateQuery, deleteQuery, primaryKey, lookupQuery;
            public int primaryKeyType;
            public boolean hasAutoKey = false, isLinked = false;
            Class<?> classType;
        }
        Map<String, String> actions = new HashMap<String, String>();
        public <T> boolean linkClassToTable(Class<T> clazz, String tableName, String primaryKey, boolean isAutoGen, ColumnDef... colOverrides) throws SQLException{
            /// To be done
            tableName = tableName.toUpperCase();
            primaryKey = primaryKey.toUpperCase();
            TableDef dbColInfo = null;
            if(!tableExists(tableName)){
                print(Level.INFO, "table does not exist - creating query ");
                dbColInfo = getColumnsFromClass(clazz, colOverrides);
                StringBuilder builder = new StringBuilder();
                //if(needToDrop)
                //    builder.append("drop table ").append(def.tableName).append(";\n");
                builder.append("create table ").append(tableName).append("(");
                for(ColumnDef cDef : dbColInfo.values()){
                    DType typ = dbTypeMap.get(cDef.dataType);
                    if(typ == null){
                        typ = null;
                    }
                    String nullPart = (cDef.allowNull ? " null" : " not null ");
                    if(cDef.columnName.equals(primaryKey))
                        nullPart = " not null"+(isAutoGen ? " auto_increment":"");
                    builder.append(cDef.columnName).append(" ").append(typ.create).append(nullPart).append(" ,");
                }
                if(primaryKey != null)
                    builder.append("primary key (").append(primaryKey).append("),");
                String qry = builder.substring(0, builder.length()-1)+")";
                try {
                    print(Level.INFO, "auto create: "+_autoCreateTables+" qry: "+qry);
                    if(_autoCreateTables){
                        int count = getConn().createStatement().executeUpdate(qry);
                        if(count > 0)
                            print(Level.INFO, "created table successfully");
                    }
                    else
                        actions.put( "dbcreate"+ actions.size(), qry);
                } catch (SQLException e) {
                    // TODO Auto-generated catch block
                    warn("Exception",e);
                }
            }
            dbColInfo = getColumnsForTable(tableName);

            if(dbColInfo == null)
                return false;
            else
                dbColInfo.tableName = tableName;
            linkClass(clazz, dbColInfo, primaryKey, isAutoGen);

            tableDef.put(clazz.getName(), dbColInfo);
            StringBuilder insertQry1 = new StringBuilder(), insertQry2 = new StringBuilder();
            insertQry1.append("insert into ").append(tableName).append("(");
            insertQry2.append(") values(");

            StringBuilder updateQry = new StringBuilder();
            updateQry.append("update ").append(tableName).append(" set ");

            for(ColumnDef d: dbColInfo.values()){
                if(!d.isAutoIncrement){
                    insertQry1.append(d.columnName).append(",");
                    insertQry2.append("?,");
                    updateQry.append(" ").append(d.columnName).append(" = ?,");
                }
            }
            dbColInfo.insertQuery= insertQry1.substring(0, insertQry1.length()-1)+insertQry2.substring(0, insertQry2.length()-1)+")";
            dbColInfo.updateQuery = updateQry.substring(0, updateQry.length()-1)+" where "+dbColInfo.primaryKey +" = ? ";
            dbColInfo.deleteQuery = "delete from "+tableName+" where "+dbColInfo.primaryKey+" = ? ";
            dbColInfo.lookupQuery = "select * from "+tableName+" where "+dbColInfo.primaryKey+" = ? ";
            return true;
        }

        public TableDef getColumnsForTable(String tableName){

            ResultSet rs = null;
            Statement stmt = null;
            try{
                stmt = getConn().createStatement();
                rs = stmt.executeQuery(" select * from "+tableName+" where 1 = 0 ");
                if(rs!=null){
                    ResultSetMetaData rsMd = rs.getMetaData();
                    return getColumnsFromResultSet(rsMd);
                }
            }catch(Exception ex){
                ex.printStackTrace();
            }finally{
                if(rs!= null)
                {
                    try {
                        rs.close();
                        stmt.close();
                    } catch (SQLException e) {}
                }
            }
            return null;
        }
        private TableDef getColumnsFromResultSet(ResultSetMetaData rsMd) throws SQLException {
            TableDef tDef = new TableDef();
            for(int i=1;i<=rsMd.getColumnCount();i++)
            {
                ColumnDef p = new ColumnDef(rsMd.getColumnName(i));
                p.dataType = rsMd.getColumnType(i);
                p.columnName = rsMd.getColumnName(i);

                p.allowNull = (rsMd.isNullable(i) != ResultSetMetaData.columnNoNulls);
                p.isAutoIncrement = rsMd.isAutoIncrement(i);
                tDef.put(p.columnName, p);
            }
            return tDef;
        }
        private TableDef getColumnsFromClass(Class<?> clazz, ColumnDef[] colOverrides){
            TableDef classCols = new TableDef();
            Map<String, ColumnDef> overrides = new HashMap<String, ColumnDef>();
            if(colOverrides != null){
                for(ColumnDef d:colOverrides)
                    overrides.put(d.fieldName.toUpperCase(), d);
            }
            for(Method m:clazz.getMethods()){
                if( (m.getName().startsWith("set") || m.getName().startsWith("get")) && !m.getName().equals("getClass")){
                    String fName = m.getName().substring(3).toUpperCase();
                    ColumnDef cDef = classCols.get(fName);
                    if(cDef == null)
                        classCols.put(fName, cDef = new ColumnDef(fName));
                    if(m.getName().startsWith("set"))
                        cDef.setMethod = m;
                    else
                        cDef.getMethod = m;
                }
            }
            for(Field f: clazz.getFields()){
                ColumnDef cDef = null;
                if(!classCols.containsKey(f.getName())){
                    String name = f.getName().toUpperCase();
                    classCols.put(name, cDef = new ColumnDef(name));
                    cDef.field = f;
                }
            }
            HashSet<String> keysToRemove = new HashSet<String>();
            for(ColumnDef cDef: classCols.values()){
                ColumnDef override = overrides.get(cDef.fieldName);

                if(override != null && override.ignore)
                    keysToRemove.add(cDef.fieldName);
                else if(cDef.field != null)
                    cDef.fieldType = cDef.field.getType();
                else if(cDef.getMethod != null)
                    cDef.fieldType = cDef.getMethod.getReturnType();
                else
                    keysToRemove.add(cDef.fieldName);

                if(cDef.fieldType != null && cDef.fieldType.isEnum())
                {
                    cDef.dataType = Types.SMALLINT;
                    cDef.enumVals = cDef.fieldType.getEnumConstants();
                }
                else if(override != null && override.dataType != null)
                    cDef.dataType = override.dataType;
                else
                    cDef.dataType = classToDataTypeMap.get(cDef.fieldType);

                if(override != null){
                    cDef.allowNull = override.allowNull;
                    cDef.columnName = override.columnName.toUpperCase();
                }
            }
            for(String k: keysToRemove)
                classCols.remove(k);
            return classCols;
        }
        private boolean tableExists(String tableName) throws SQLException{
            DatabaseMetaData meta = getConn().getMetaData();
              ResultSet res = meta.getTables(null, null, tableName, new String[] {"TABLE"});
              boolean exists = res.next();
              res.close();
              return exists;
        }
        private TableDef linkClass(Class<?> clazz, TableDef dbDef, String primaryKey, boolean isAutoIncrement) throws SQLException{
            TableDef classCols = getColumnsFromClass(clazz, null);

            for(ColumnDef p: dbDef.values())
            {
                ColumnDef classCol = classCols.get(p.columnName);
                if(classCol == null)// || (classCol.isAutoIncrement != p.isAutoIncrement) || (classCol.allowNull != p.allowNull))
                    print(Level.WARNING, "no mapping for column "+p.columnName+" in class "+clazz.getName());
                // not doing type check for now - it has to allow for compatible size different columns
                else{
                    p.fieldType = classCol.fieldType;
                    p.getMethod = classCol.getMethod;
                    p.setMethod = classCol.setMethod;
                    p.enumVals = classCol.enumVals;
                    p.field = classCol.field;
                }

                classCols.remove(p.columnName);
            }
            if(primaryKey != null){
                ColumnDef pKey = dbDef.get(primaryKey.toUpperCase());
                if(pKey != null){
                    dbDef.primaryKey = primaryKey.toUpperCase();
                    dbDef.hasAutoKey = isAutoIncrement;
                    dbDef.primaryKeyType = pKey.dataType;
                }
                else
                    throw new SQLException("Primary key field "+primaryKey+" not found in table "+dbDef.tableName);
            }
            if(!classCols.isEmpty())
            {
                String alterTableQuery = "alter table "+dbDef.tableName+" add (";
                for(ColumnDef c: classCols.values()){
                    alterTableQuery += c.columnName+ " "+ classToDataTypeMap.get(c.fieldType)+" null,";
                }
                print(Level.WARNING, "Need to alter table "+dbDef.tableName+" " +alterTableQuery);
            }
            return dbDef;
        }
        private <T> List<Object> getValues(T val, TableDef colDefs) throws IllegalAccessException,InvocationTargetException, SQLException {
            List<Object> vals = new ArrayList<Object>();
            for(ColumnDef d: colDefs.values()){
                if(!d.isAutoIncrement){
                    Object o = getValue(val, d);
                    vals.add(o);
                }
            }
            return vals;
        }

        private <T> Object getValue(T val, ColumnDef d)
                throws IllegalAccessException, InvocationTargetException {
            Object o = null;
            if(d.getMethod != null)
                o = d.getMethod.invoke(val);
            else
                o = d.field.get(val);
            return o;
        }

        public <T> boolean insert(T val) throws SQLException{
            TableDef def = tableDef.get(val.getClass().getName());
            print(Level.FINE, def.insertQuery);
            try {
                List<Object> vals = getValues(val, def);
                Integer newKey = executeUpdate(def.insertQuery, def.hasAutoKey, vals.toArray());
                if(def.hasAutoKey){
                    if(newKey != null && newKey>=0){
                        setValueInObject(def.primaryKey, newKey, val, def);
                        return true;
                    }
                }
                else
                    return (newKey >0);
            } catch (Exception e) {
                warn("Exception",e);
                throwSQLException(e);
            }
            return false;
        }
        public <T> boolean update(T val) throws SQLException{
            TableDef def = tableDef.get(val.getClass().getName());
            print(Level.FINE, def.updateQuery);
            try {
                List<Object> vals = getValues(val, def);
                Object pKeyValue = getValue(val, def.get(def.primaryKey));
                vals.add(pKeyValue);
                return executeUpdate(def.updateQuery, vals.toArray());
            } catch (Exception e) {
                warn("Exception",e);
                throwSQLException(e);
            }
            return false;
        }
        public <T> boolean delete(Class<T> clazz, Object key) throws SQLException{
            TableDef def = tableDef.get(clazz.getName());
            try {
                return executeUpdate(def.deleteQuery, key);
            } catch (IllegalArgumentException e) {
                warn("Exception",e);
            }
            return false;
        }
        public <T> T getById(Class<T> clazz, Object key) throws SQLException{
            TableDef def = tableDef.get(clazz.getName());
            try {
                return readOne(clazz, def.lookupQuery, key);
            } catch (IllegalArgumentException e) {
                warn("Exception",e);
            }
            return null;
        }
        public class MyMap extends TreeMap<String,Object>{private static final long serialVersionUID = 1L;}
        private <T> void setValueInObject(String field, Object val, T obj, Map<String,ColumnDef> tblDef) throws SQLException{
            if(obj instanceof MyMap)
                ((MyMap) obj).put(field, val);
            else{
                ColumnDef cDef = tblDef.get(field);

                try {
                    if(cDef.enumVals != null)
                    {
                        short v = (Short)val;
                        if(v<cDef.enumVals.length)
                            val = cDef.enumVals[v];
                    }
                    else if(cDef.dataType == Types.BLOB || cDef.dataType == Types.VARBINARY)
                    {
                        if(val instanceof Blob)
                        {
                            Blob b = (Blob)val;
                            if(b.length() > 0)
                                val = b.getBytes(1L, (int)b.length());
                        }
                        else
                            print(Level.WARNING, "Could not map blob type to bytes "+val);
                    }
                    if(cDef.setMethod != null && val != null)
                        cDef.setMethod.invoke(obj, val);
                    else if(cDef.field != null)
                            cDef.field.set(obj, val);
                } catch (Exception e) {
                    warn("Exception",e);
                    throwSQLException(e);
                }
            }
        }
        private void throwSQLException(Exception ex) throws SQLException
        {
            if(ex instanceof SQLException)
                throw ((SQLException)ex);
            else
                throw new SQLException(ex);
        }
        public <T> List<T> readList(Class<T> clazz, String query, Object... data) throws SQLException{
            return readListLimit(clazz, query, Integer.MAX_VALUE, data);
        }
        public <T> List<T> readListLimit(Class<T> clazz, String query, int limit, Object... data) throws SQLException{
            List<T> list = new ArrayList<T>();
            ResultSet rs = null;
            PreparedStatement stmt = null;
            try
            {
                TableDef tblDef = tableDef.get(clazz.getName());
                String tableName = (tblDef == null ? clazz.getSimpleName().toUpperCase() : tblDef.tableName);
                if( query == null || (!query.trim().toLowerCase().startsWith("select") && !query.trim().toLowerCase().startsWith("call")))
                    query = " select * from "+tableName+ (query == null ? "" : " where " + query);
                stmt = getConn().prepareStatement(query);
                for (int i = 0; i < data.length; i++) {
                    setValueInStatement(stmt, i+1, data[i]);
                }
                rs = stmt.executeQuery();
                if(tblDef == null)
                    tblDef = tableDef.get(query);
                ResultSetMetaData md = rs.getMetaData();
                if(tblDef == null){
                    tblDef = linkClass(clazz, getColumnsFromResultSet(md), null, false);
                    tableDef.put(query, tblDef);
                }
                int columns = md.getColumnCount();
                list = new ArrayList<T>();
                int count = 0;
                while (rs.next()) {
                    T row = clazz.newInstance();
                    for (int i = 1; i <= columns; ++i) {
                        setValueInObject(md.getColumnName(i), rs.getObject(i), row, tblDef);
                    }
                    list.add(row);
                    if(++count > limit)
                        break;
                }
            }
            catch(SQLException ex){
                throw ex;
            }
            catch(Exception ex){
                print(Level.WARNING, "Exception in query "+query, ex);
                throw new SQLException("Got Exception ", ex.getMessage());
            }
            finally{
                if(stmt != null)
                    stmt.close();
                if(rs != null)
                    rs.close();
            }
            return list;
        }

        private void setValueInStatement(PreparedStatement stmt, int index, Object val) throws SQLException {
            if(val instanceof Enum)
                stmt.setObject(index, ((Enum<?>)val).ordinal());
            else
                stmt.setObject(index, val);
        }
        class DType{
            int dbType;
            String create;
        }
        private Map<Class<?>, Integer> classToDataTypeMap = new HashMap<Class<?>, Integer>();
        private Map<Integer, DType> dbTypeMap = new HashMap<Integer, DType>();
        private void addDbType(int dbType, String create, Class<?>[] classes)
        {
            DType dtype = new DType();
            dtype.dbType = dbType;
            dtype.create = create;
            if(classes != null){
                for(Class<?> c : classes)
                    classToDataTypeMap.put(c, dbType);
            }
            dbTypeMap.put(dbType, dtype);
        }

        public void init(){
            addDbType(Types.BOOLEAN, "bool", new Class<?>[]{Boolean.class, boolean.class});
            addDbType(Types.VARCHAR, "varchar(255)", new Class<?>[]{String.class});
            addDbType(Types.LONGNVARCHAR, "longvarchar", null);
            addDbType(Types.TIMESTAMP, "timestamp", new Class<?>[]{java.util.Date.class, java.sql.Date.class});
            addDbType(Types.INTEGER, "int", new Class<?>[]{Integer.class, int.class});
            addDbType(Types.BIGINT, "long", new Class<?>[]{Long.class, long.class});
            addDbType(Types.SMALLINT, "smallint", new Class<?>[]{Short.class, short.class, Enum.class});
            addDbType(Types.CHAR, "char", new Class<?>[]{Character.class});
            addDbType(Types.BLOB, "blob", new Class<?>[]{byte[].class, Blob.class});
            addDbType(Types.FLOAT, "float", new Class<?>[]{float.class, Float.class});
            addDbType(Types.DOUBLE, "double", new Class<?>[]{double.class, Double.class});
            addDbType(Types.CLOB, "clob", new Class<?>[]{char[].class, Clob.class});
        }
    }
    private Db db;
    public Db getDb() {
        if(db == null)
            throw new NullPointerException("DB not initialized");
        return db;
    }

    public interface ITemplate{
        public Mustache setDefaultValue(String val);
        public Mustache addTemplateLocation(String uri);
        public void disableCaching();
        public String execute(String shell, String page, Map<String,Object> data);
    }
    public static class Mustache implements ITemplate {
        final Pattern matchImports = Pattern.compile("(\\{\\{> *([a-zA-Z0-9_\\.\\/]+) *\\}\\})");
        final Pattern matchCondition = Pattern.compile("(\\{\\{#([a-zA-Z0-9_\\.]+) *(: *\\w *)?\\}\\})");
        final Pattern matchValue = Pattern.compile("(\\{\\{([a-zA-Z0-9_\\.]+)\\}\\})");
        List<String> locations = new ArrayList<String>();
        String defaultValue;
        private ClassLoader classLoader;
        public Mustache(){}
        public Mustache(ClassLoader clsLoader){this.classLoader = clsLoader; }
        public Mustache setDefaultValue(String val){defaultValue = val;return this;}
        public Mustache addTemplateLocation(String uri){locations.add(uri); return this;}
        public void disableCaching(){caching = false;}
        private boolean caching = true;

        private HashMap<String, String> cache = new HashMap<String, String>();
        private String loadTemplate(String tag)
        {
            try {
                if(locations.isEmpty())
                {
                    InputStream strm = null;
                    if(classLoader != null)
                    {
                        strm = classLoader.getResourceAsStream(tag);
                        print(Level.INFO, "looking in :"+tag+" "+classLoader+" got:"+strm);
                    }
                    if(strm == null)
                    {
                        strm = Thread.currentThread().getContextClassLoader().getResourceAsStream(tag);
                        print(Level.INFO, "looking in :"+tag+" "+Thread.currentThread().getContextClassLoader()+" got:"+strm);
                    }
                    if(strm == null)
                    {
                        strm = this.getClass().getClassLoader().getResourceAsStream(tag);
                        print(Level.INFO, "looking in :"+tag+" "+this.getClass().getClassLoader()+" got:"+strm);
                    }
                    if(strm == null)
                        print(Level.INFO, "could not find file in classpath " + tag);
                    else
                        return readStream(strm);
                }
                else
                {
                    for(String l : locations)
                    {
                        File f = new File("."+l+"/"+tag+".html");
                        if(f.exists())
                        {
                            InputStream fis = new FileInputStream(f);
                            String data = readStream(fis);
                            fis.close();
                            return data;
                        }
                    }
                    print(Level.WARNING, "File not found "+ tag);
                }
                if("default".equals(tag))
                    return defaultShell;
            } catch (IOException e) {
                print(Level.WARNING, "Exception loading template "+tag, e);
            }

            return null;
        }
        private String readStream(InputStream fis) {
            Scanner scanner = new Scanner(fis,"UTF-8");
            String data = scanner.useDelimiter("\\A").next();
            scanner.close();
            return data;
        }

        private void recursiveEval(StringBuilder bldr, LinkedList<Map<String, Object>> stack)
        {
            Matcher condMatcher = matchCondition.matcher(bldr.toString());
            while(condMatcher.find())
            {
                String enumeration = condMatcher.group(2);
                String var = condMatcher.group(3);
                if(var != null)
                    var = var.replaceAll("[ :]", "");
                else
                    var = "_this";
                String enumEnd = "{{/"+enumeration+"}}";

                int sectionEnd = bldr.indexOf(enumEnd, condMatcher.end());

                String innerData = bldr.substring(condMatcher.end(), sectionEnd);
                Object val = getFromStack(enumeration, stack);

                boolean passed = false;
                boolean isList = false;
                if(val instanceof Boolean)
                    passed = ((Boolean)val).booleanValue();
                else if(val instanceof Iterable<?>)
                    isList = true;
                else if(val instanceof Object[]){
                    val = Arrays.asList((Object[])val);
                    isList = true;
                }
                else if(val != null)
                    passed = true;

                if(isList)
                {
                    StringBuffer replacedData = new StringBuffer();
                    int index = 0;
                    for (Object o : ((Iterable<?>)val)) {
                        index++;
                        StringBuilder rowBuilder = new StringBuilder(innerData);
                        Map<String, Object> stackFrame = new HashMap<String, Object>();
                        stackFrame.put(var, o);
                        stackFrame.put("_index", index);
                        stack.push(stackFrame);
                        recursiveEval(rowBuilder, stack);
                        replacedData.append(rowBuilder);
                        stack.pop();
                    }
                    bldr.replace(condMatcher.start(), sectionEnd+enumEnd.length(), replacedData.toString());
                }
                else if(passed)
                {
                    StringBuilder rowBuilder = new StringBuilder(innerData);
                    Map<String, Object> stackFrame = new HashMap<String, Object>();
                    stackFrame.put(var, val);
                    stack.push(stackFrame);
                    recursiveEval(rowBuilder, stack);
                    bldr.replace(condMatcher.start(), sectionEnd+enumEnd.length(), rowBuilder.toString());
                    stack.pop();
                }
                else
                    bldr.replace(condMatcher.start(), sectionEnd+enumEnd.length(), "");

                condMatcher = matchCondition.matcher(bldr.toString());
            }
            updateValue(bldr, stack);
        }
        private class Mapper{
            Method method;
            Field field;
        }
        private Map<String, Mapper> mappers = new HashMap<String, Mapper>();
        private void updateValue(StringBuilder html, LinkedList<Map<String,Object>> stack)
        {
            Matcher varMatcher = matchValue.matcher(html);
            while(varMatcher.find())
            {
                String key = varMatcher.group(2);
                Object val = getFromStack(key, stack);
                String outVal = null;
                outVal = (val != null ? val.toString() : defaultValue);

                if(outVal != null)
                {
                    html.replace(varMatcher.start(1), varMatcher.end(1), outVal);
                    varMatcher = matchValue.matcher(html);
                }
                else // if we dont do a replace - the varMather.matcher will not work - so have to keep doing find to get next match
                {}
            }
        }
        private Object getFromStack(String key, LinkedList<Map<String,Object>> stack)
        {
            if(!stack.isEmpty()){
                Object refVal = stack.get(0).get("_this");
                if(refVal != null){
                    Object val = getValue(key, refVal);
                    if(val != null)
                        return val;
                }
            }
            String firstPart = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;

            for (Map<String,Object> map : stack) {
                if(map.containsKey(firstPart))
                    return getValue(key, map);
            }

            return null;
        }
        private Object getValue(String key, Object data)
        {
            Object o = null;
            String remainingKey = null;
            if(key.contains("."))
            {
                remainingKey = key.substring(key.indexOf('.')+1);
                key = key.substring(0, key.indexOf('.'));
            }
            if(data instanceof Map<?,?>)
            {
                o = ((Map<?,?>)data).get(key);
            }
            else if(data != null)
            {
                String tag = data.getClass().getName()+"."+key;
                Mapper mapper = mappers.get(tag);
                if(mapper == null)
                {
                    mappers.put(tag, mapper = new Mapper());
                    String methodName = "get"+key.substring(0, 1).toUpperCase()+key.substring(1);
                    for(Method m:data.getClass().getMethods()){
                        if(m.getName().equals(methodName))
                            {mapper.method = m;break;}
                    }
                    if(mapper.method == null)
                    {
                        for(Field f : data.getClass().getFields())
                        {
                            if(f.getName().equals(key))
                                {mapper.field = f;break;}
                        }
                    }
                }
                try {
                    if(mapper.method != null)
                        o = mapper.method.invoke(data);
                    else if(mapper.field != null)
                        o = mapper.field.get(data);
                } catch (Exception e) {
                    warn("Exception",e);
                    return null;
                }

            }
            if(remainingKey == null || o == null)
                return o;
            else
                return getValue(remainingKey, o);
        }

        public String compile(String template, String shellTemplateName)
        {
            print(Level.INFO, "Compiling " + (template.startsWith("{{>") ? template : "some html"));

            StringBuilder compiledTemplate = new StringBuilder();

            if(shellTemplateName != null)
            {
                String shellText = null;
                if(shellTemplateName != null)
                    shellText = loadTemplate(shellTemplateName);
                if(shellText != null)
                    compiledTemplate.append(shellText);
            }


            int idx = compiledTemplate.indexOf("{{main}}");
            if(compiledTemplate.length()>0 && idx >=0)
                compiledTemplate.replace(idx, idx+8, template);
            else
                compiledTemplate.append(template);

            Matcher matcher = matchImports.matcher(compiledTemplate.toString());
            while(matcher.find())
            {
                String templName = matcher.group(2);
                String data = loadTemplate(templName);
                if(data == null)
                    print(Level.WARNING, "could not find file " + templName);
                else
                {
                    compiledTemplate.replace(matcher.start(1), matcher.end(1), data);
                    // check again if the imports contain imports
                    matcher = matchImports.matcher(compiledTemplate.toString());
                }
            }
            String result = compiledTemplate.toString();
            if(caching)
            {
                String key = (shellTemplateName == null? template : template+shellTemplateName);
                cache.put(key, result);
            }
            return result;
        }
        public String execute(String template, String shell, Map<String, Object> respData){
            String key = (shell == null? template : template+shell);
            String compiledTemplate = cache.get(key);

            if(compiledTemplate == null)
                compiledTemplate = compile(template, shell);

            StringBuilder bldr = new StringBuilder(compiledTemplate);
            LinkedList<Map<String, Object>> stack = new LinkedList<Map<String, Object>>();
            stack.push(respData);
            recursiveEval(bldr, stack);
            return bldr.toString();
        }
    }

    public interface IController {
        public Response execute(Request req) throws Exception;
    }

    public static int strToInt(String val) {
        return (val != null && Pattern.matches("^[0-9]+$", val)) ? Integer.parseInt(val) : Integer.MIN_VALUE;
    }
    public static class Request{
        HttpServletRequest req;
        HttpServletResponse resp;
        public HttpServletRequest rawReq(){return req;}
        public HttpServletResponse rawResp(){return resp;}
        Map<String, String> pathParams = new HashMap<String, String>();
        public String pathParam(String key){return pathParams.get(key);}
        public String param(String key){return req.getParameter(key);}
    }
    public static abstract class Response {
        public Map<String, String> headers = new HashMap<String, String>();
        public int status = 200;
        public Map<String, String> getHeaders(){return headers;}
        public void setHeader(String key, String url) {headers.put(key, url);}
        public String format;
        public abstract String getResponse() throws IOException;
    }
    private class RedirectResponse extends Response {
        public RedirectResponse(String url, int status){this.status = status; setHeader("Location", url);}
        public String getResponse(){return "redirecting";}
    }
    private class ControllerInfo {
        public List<String> patternParams = new ArrayList<String>();
        public IController controller;
        public String route, method, testString;
        public Pattern pattern;
        public boolean isFilter;
    }

    List<ControllerInfo> routes = new ArrayList<ControllerInfo>();

    public void initDb(String dbInfo){
        if(dbInfo == null || "".equals(dbInfo))
            info("db connection not created - if connection needed should be "+dbConfigSample);
        else{
            String[] parts = dbInfo.split("\\|");
            initDb(parts[0], parts[1], parts[2], parts[3]);
        }
    }

    public void initDb(String connString, String driver, String user, String pwd){
        db = new Db(connString, driver, user, pwd);
    }

    public static final String GET = "GET", POST = "POST";

    public void addController(String method, String route, String testString, IController controler) {
        registerRoute(method, route, testString, false, controler);
    }

    public void addFilter(String route, String testString, IController controler) {
        registerRoute(null, route, testString, true, controler);
    }

    private void registerRoute(String method, String route, String testString, boolean isFilter, IController controler) {
        ControllerInfo cInfo = new ControllerInfo();
        cInfo.route = route.replaceAll("\\*", ".*");
        cInfo.method = method;
        cInfo.testString = testString;
        cInfo.controller = controler;
        cInfo.isFilter = isFilter;
        Matcher matcher = keyPattern.matcher(cInfo.route);
        if (!matcher.matches())
            cInfo.pattern = Pattern.compile(cInfo.route +"$");
        else {
            for (int i = 0; i < matcher.groupCount(); i++)
                if (!cInfo.patternParams.contains(matcher.group(i + 1)))
                    cInfo.patternParams.add(matcher.group(i + 1));
            String pattern = route;
            for (String patternParam : cInfo.patternParams)
                pattern = pattern.replaceFirst(":" + patternParam, "([a-zA-Z0-9_]*)");
            cInfo.pattern = Pattern.compile(pattern +"$");
        }
        print(Level.INFO, "Added "+(cInfo.isFilter ? "filter " : "route ") + route);

        routes.add(cInfo);
    }
    String staticFilePath = "/public/";
    private static String defaultShell = "<html><head><link rel=\"stylesheet\" href=\"http://yui.yahooapis.com/pure/0.5.0/pure-min.css\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"+
            "</head><body><div id=\"main\"><div class=\"header\"><div class=\"home-menu pure-menu pure-menu-open pure-menu-horizontal\">" +
            "</div></div><div class=\"content\">{{main}}</body></html>";
    public void setStaticFilePath(String p){staticFilePath =p;}
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String uri = req.getPathInfo();
        if(uri == null)
            uri = req.getServletPath();
        if(uri.endsWith("favicon.ico"))
            return;
        if(uri.startsWith(staticFilePath))
            return;
        if(prefixToIgnore != null && prefixToIgnore.length() > 0 && uri.startsWith(prefixToIgnore))
            uri = uri.substring(prefixToIgnore.length());

        try {
            Request addRequest = new Request();
            addRequest.req = req;
            addRequest.resp = resp;
            String method = req.getMethod().toUpperCase();
            Response val = processRequest(uri, addRequest, method);

            if (val != null)
            {
                if (val.status == -1){
                    String url = val.getHeaders().get("Location");
                    info("request generate redirect to "+url);
                    val = processRequest(url, addRequest, method);
                    if(val == null)
                        throw new Exception("redirect failed - no response");
                    else if(val.status == -1)
                        throw new Exception("multiple redirect not allowed to "+val.getResponse());
                }

                if(val != null) {
                    Response respObject = (Response) val;
                    resp.setStatus(respObject.status);
                    String res = respObject.getResponse();
                    for(String k : respObject.getHeaders().keySet())
                        resp.setHeader(k, respObject.getHeaders().get(k));
                    resp.setContentLength(res.length());
                    String respType = "text/html";
                    if (respObject.format != null)
                        respType = respObject.format;
                    resp.setContentType(respType);
                    OutputStream strm = resp.getOutputStream();
                    strm.write(res.getBytes());
                    strm.flush();
                    strm.close();
                }
            }

        } catch (Exception ex) {
            print(Level.WARNING, "Exception in handling request " + uri, ex);
            resp.sendError(500, "Internal server error");
        }
    }

    Response processRequest(String uri, Request addRequest, String method) throws Exception {
        Response val = null;
        for (ControllerInfo mInfo : routes) {
            Matcher matcher = mInfo.pattern.matcher(uri);
            if ((mInfo.isFilter || method.equals(mInfo.method)) && matcher.matches()) {
                for (int i = 0; i < matcher.groupCount(); i++)
                    addRequest.pathParams.put(mInfo.patternParams.get(i), matcher.group(i + 1));
                print(Level.FINE, "request "+uri+" forwarded to "+mInfo.route+" ");
                val = mInfo.controller.execute(addRequest);
                // can match multiple filters/ controllers till one of them handles it
                if(val != null)
                    break;
            }
        }
        return val;
    }

    public ITemplate mustache  = new Mustache();
    public class TemplResponse extends Response {
        private String shell, page;
        private Map<String, Object> data = new HashMap<String, Object>();
        public TemplResponse(String page){this.page = page;}
        public TemplResponse(String shellFile, String page) {
            this.shell = shellFile;
            this.page = page;
        }
        public TemplResponse add(String key, Object val){this.data.put(key, val); return this;}
        @Override
        public String getResponse() throws IOException {
            return mustache.execute(page, shell, data);
        }
    }
    public TemplResponse respond(String shell, String page){
        return new TemplResponse(shell, page);
    }
    public Response redirect(String url, int status){
        return new RedirectResponse(url, status);
    }
    public TemplResponse halt(int code,String error){
        TemplResponse resp = new TemplResponse(null, "Error: "+error);
        resp.status=code;
        return resp;
    }
    public interface IConverter<T> {
        String serialize(T obj) throws Exception;
        T deserialize(InputStream stream) throws Exception;
        T deserialize(String text) throws Exception;
        String serializeKeys(List<String> keys) throws Exception;
    }
    public class XmlConverter<T> implements IConverter<T>  {
        Class<T> clazz;
        JAXBContext jaxbContext;
        Marshaller jaxbMarshaller, keyMarshaller;
        Unmarshaller jaxbUnmarshaller;
        final ArrayList<String> sample = new ArrayList<String>();
        public XmlConverter(Class<T> clazz) throws Exception{
            this.clazz = clazz;
            jaxbContext = JAXBContext.newInstance(clazz);
            jaxbMarshaller = jaxbContext.createMarshaller();
             jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
             jaxbUnmarshaller = jaxbContext.createUnmarshaller();
             keyMarshaller = JAXBContext.newInstance(sample.getClass()).createMarshaller();
        }
        @Override public String serialize(T obj) throws Exception{
            StringWriter w = new StringWriter();
            jaxbMarshaller.marshal(obj, w);
            return w.toString();
        }

        @Override public T deserialize(InputStream stream) throws Exception {
            return (T)jaxbUnmarshaller.unmarshal(new StreamSource(stream), clazz).getValue();
        }

        @Override public T deserialize(String text) throws Exception {
            return (T)jaxbUnmarshaller.unmarshal(new StreamSource(new StringReader(text)), clazz).getValue();
        }
        @Override public String serializeKeys(List<String> keys) throws Exception {
            StringWriter w = new StringWriter();
            keyMarshaller.marshal(keys, w);
            return w.toString();
        }
    }
    private List<Object> lookupPathParams(final String[] acceptParams,
            Request req) throws SQLException {
        List<Object> params = new ArrayList<Object>();
        if(acceptParams != null){
            for(int i=0;i<acceptParams.length;i++){
                String val = req.pathParam(acceptParams[i]);
                if(val != null)
                    params.add(req.pathParam(acceptParams[i]));
                else
                    throw new SQLException("Parameter not found: "+acceptParams[i]);
            }
        }
        return params;
    }
    public <T> boolean exposeRestService(String tag, final Class<T> clazz, final String filter, final String[] acceptParams) throws Exception{
        final IConverter<T> converter = new XmlConverter<T>(clazz);
        Db.TableDef def = getDb().tableDef.get(clazz.getName());
        if(def == null)
            throw new SQLException("Rest server can only use tables that have been linked by linkClassToTable( ... )");
        final String pKey =  def.primaryKey;
        addController(GET, tag, null, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                List<Object> params = lookupPathParams(acceptParams, req);
                List<? extends Map<String, Object>> vals = getDb().readListLimit(filter, Integer.MAX_VALUE, params.toArray());
                List<String> keyStrings = new ArrayList<String>();
                if(pKey != null){
                    for(Map<String,Object> v : vals){
                        Object key = v.get(pKey);
                        if(key != null)
                            keyStrings.add(key.toString());
                    }
                    String data = converter.serializeKeys(keyStrings);
                    TemplResponse resp = new TemplResponse(data);
                    resp.format = "text/xml";
                    return resp;
                }
                return halt(404, "not found");
            }
        });
        addController(GET, tag+"/:id", null, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                List<Object> params = lookupPathParams(acceptParams, req);
                String id = req.param(pKey);
                if(id != null){
                    params.add(id);
                    T val = getDb().readOne(clazz, filter, params);
                    String data = converter.serialize(val);
                    TemplResponse resp = new TemplResponse(data);
                    resp.format = "text/xml";
                    return resp;
                }
                return halt(404, "not found");
            }
        });
        return false;
    }
    public boolean exposeTable(final String tag, final String fieldList, final String query, final String[] acceptParams, String edtKey){
        final String editKey = (edtKey == null? null : edtKey.toUpperCase());
        addController(GET, tag+"/list", null, new IController() {
            List<String> fields = null;
            @Override
            public Response execute(Request req) throws Exception {
                if(fields == null && fieldList != null){
                    fields = new ArrayList<String>();
                    for(String f: fieldList.split(","))
                        fields.add(f.toUpperCase());
                }
                List<Object> params = lookupPathParams(acceptParams, req);
                List<? extends Map<String,Object>> vals = getDb().readListLimit(query, Integer.MAX_VALUE, params.toArray());

                if(fields == null && !vals.isEmpty()){
                    fields = new ArrayList<String>();
                    for(String s: vals.get(0).keySet())
                        fields.add(s);
                }

                StringBuilder sbldr = new StringBuilder("<table><tr>");
                for(String f: fields)
                    sbldr.append("<th>").append(f).append("</th>");
                sbldr.append("</tr>");
                for(Map<String, Object> val : vals){
                    sbldr.append("<tr>");
                    for(String f : fields){
                        sbldr.append("<td>").append(val.get(f)).append("</td>");
                    }
                    if(editKey != null)
                        sbldr.append("<td><a href='").append(tag).append("/edit/").append(val.get(editKey)).append("'>Edit</a></td>");
                    sbldr.append("</tr>");
                }
                sbldr.append("</table>");
                return new TemplResponse(null, sbldr.toString());
            }
        });
        if(editKey != null){
            addController(GET, tag+"/edit/:id", null, new IController() {
                @Override
                public Response execute(Request req) throws Exception {
//                    if(isEditable){
//                        sbldr.append("<tr><form action='").append(tag).append("/edit' method='POST'>");
//                        for(String f: fields){
//                            sbldr.append("<td>").append(d.isEditable?"<input type='text' name='"+f+"'>":d.value).append("</td>");
//                        }
//
//                        sbldr.append("<td><input type=submit text='submit' /></form></tr>");
//                    }
                    return null;
                }
            });
        }
        return false;
    }
    private String addParamsToRoutesUrl(String route, String testString) {
        String[] prms = testString.split(";");
        String queryString = "";
        for(String p: prms)
        {
            String[] prts = p.split("=");
            if(prts.length>1 && route.contains(":"+prts[0]+"" ))
                route = route.replaceFirst(":"+prts[0], prts[1]);
            else
                queryString = queryString+p+"&";
        }
        if(queryString.length() > 0)
            route = route +"?"+queryString;
        return route;
    }

    public String prefixToIgnore="", fullUrlPrefix="", targetUrl, adminTag;
    public String mapUrl(String subUrl){return fullUrlPrefix+subUrl;}
    public void setFullUrl(String refUrl){fullUrlPrefix = refUrl;}
    public void setTargetUrl(String url){targetUrl = url;}
    public void ignorePrefix(String prefix) {this.prefixToIgnore = prefix;}
    public void initManager(String tag){initManager(tag, true);}
    public void initManager(final String tag, final boolean showInline){
        adminTag = tag;
        if(adminTag == null)
        	return;
        
        registerRoute(GET, tag, null, false, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                HtmlElement body = new HtmlDocument().html().body();
                HtmlElement table = body.table("main").add("width", "100%"), row = table.row();
                HtmlElement list = row.col().addElement("ul");
                list.addElement("li").hyperlink("http://oxyj.com/micro", "<img src='http://oxyj.com/images/oxyj-logo.png'></img>OxyJ Micro", "");
                HtmlElement dbList = list.addElement("li");
                if(db == null)
                    dbList.setInnerText("Database is not connected");
                else if(db.actions.size() == 0)
                    dbList.setInnerText("Database schema is up to date");
                else {
                    dbList.setInnerText("Database Schema is different : "+db.actions.size());
                    HtmlElement dbUpdates = dbList.addElement("ul");
                    for(String q:db.actions.keySet())
                        dbUpdates.addElement("li").setInnerText(db.actions.get(q)).hyperlink(mapUrl(tag+"/execUpdate/"+q), "Execute", "logFrame");
                }
                HtmlElement testList = list.addElement("li").setInnerText("Tests").addElement("ul");

                for(ControllerInfo c : routes){
                    String refUrl = c.route;
                    if(c.testString != null && c.testString.length()>0){
                        refUrl = addParamsToRoutesUrl(refUrl, c.testString);
                        if(targetUrl == null)
                            targetUrl = refUrl;
                    }
                    if(c.testString != null)
                        testList.addElement("li").hyperlink(mapUrl(refUrl), c.route+" ( "+ c.testString+" )", "execFrame");
                }

                list.addElement("li").hyperlink(mapUrl(tag+"/logs"), "Refresh Logs", "logFrame");
                list.addElement("li").hyperlink(mapUrl(tag+"/reload"), "Reload App", "");

                row.col().addElement("iframe", "name", "logFrame", "src", mapUrl(tag+"/logs"), "width","100%");
                if(showInline)
                    body.addElement("iframe", "name", "execFrame","src",mapUrl(targetUrl),"width","100%", "height","100%");
                return respond("default", body.doc.toString());
            }
        });
        enableLogView(tag+"/logs");
        registerRoute(GET, tag+"/execUpdate/:qid", null, false, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                String qid = req.pathParam("qid");
                String qry = db.actions.get(qid);

                String result = getDb().executeUpdate(qry) ? "success":"failed";
                info("execution result :"+result);
                return redirect(mapUrl(tag+"/logs"), 302);
            }
        });
        registerRoute(GET, tag+"/reload", null, false, new IController() {
            @Override
            public Response execute(Request req) throws Exception {
                if(webappHandle != null) {
                    info("Reloading webapp ");
                    ((org.eclipse.jetty.webapp.WebAppContext)webappHandle).stop();
                    ((org.eclipse.jetty.webapp.WebAppContext)webappHandle).start();
                    info("Reloaded webapp "+new Date().toString());
                    return redirect(mapUrl(tag), 302);
                }
                else
                    return respond(null, "not running standalone - cannot reload");
            }
        });
    }

    public class HtmlElement{
        String type, innerText;
        HtmlDocument doc;
        Map<String, String> attrs = new HashMap<String,String>();
        public HtmlElement(String key, HtmlDocument doc){this.type = key;this.doc =doc;}
        public HtmlElement add(String key, String value){
            attrs.put(key, value);
            if("id".equals(key))
                doc.idMap.put(value, this);
            return this;
        }
        List<HtmlElement> elems = new ArrayList<HtmlElement>();
        private HtmlElement addElement(String type, String ... args){
            HtmlElement elem = new HtmlElement(type, doc);
            for(int i=0;i<args.length-1;i=i+2){
                elem.add(args[i], args[i+1]);
            }
            if(doc.root == null)
                doc.root = elem;
            else
                elems.add(elem);

            return elem;
        }
        public HtmlElement setInnerText(String text){this.innerText = text; return this;}
        public HtmlElement html() { if(doc.root != null) return null; return addElement("html");}
        public HtmlElement body() { return addElement("body");}

        public HtmlElement div(String id, String styleClass, String innerText){return addElement("div", "class", styleClass,"id", id).setInnerText(innerText);}

        public HtmlElement textInput(String name, String currValue, String validation){
            HtmlElement elem = addElement("input", "type","text", "value", currValue);
            if(validation != null)
                elem.add("onchange", validation);
            return elem;
        }

        public HtmlElement button(String buttonText, String action){
            HtmlElement elem = addElement("input", "type", "button", "text", buttonText);
            if(action != null)
                if("submit".equals(action.toLowerCase()))
                    elem.add("type", "submit");
                else
                    elem.add("onclick", action);

            return elem;
        }
        public HtmlElement hyperlink(String dest, String text, String target){HtmlElement elem = addElement("a", "href", dest, "target", target == null? "_blank" : target); elem.innerText = text; return elem;}
        public HtmlElement form(String action, boolean postMethod){return addElement("form", "action", action, "method", postMethod? "POST":"GET");}
        public HtmlElement dropdown(String id){return addElement("select", "id", id);}
        public HtmlElement checkBox(String id){return addElement("input", "type", "checkbox", "id", id);}

        public HtmlElement table(String id){return addElement("table", "id", id);}
        public HtmlElement row(){return addElement("tr");}
        public HtmlElement col(){return addElement("td");}

        void toString(StringBuilder bldr){
            bldr.append('<').append(type);
            for(String k:attrs.keySet())
                bldr.append(' ').append(k).append("=\"").append(attrs.get(k)).append("\"");
            bldr.append('>');
            if(innerText != null)
                bldr.append(innerText);
            for(HtmlElement e:elems)
                e.toString(bldr);
            bldr.append("</").append(type).append('>');
        }
        public HtmlDocument root(){
            return doc;
        }

    }
    public class HtmlDocument{
        HtmlElement root = null;
        Map<String, HtmlElement> idMap = new HashMap<String, HtmlElement>();

        public String toString(){
            if(root == null)
                return "HtmlDocument error:no html";
            StringBuilder bldr = new StringBuilder();
            root.toString(bldr);
            return bldr.toString();
        }
        public HtmlElement html() {
            root = new HtmlElement("html", this);
            return root;
        }
        public HtmlElement div(){
            root = new HtmlElement("div", this);
            return root;
        }
    }
    private static String dbConfigSample = "<init-param>\n<param-name>DB</param-name>\n<param-value>jdbc-conn-string|driver-class|username|password</param-value>\n</init-param>\n",
            adminConfigSample = "<init-param>\n<param-name>ADMIN_PAGE</param-name>\n<param-value>/myadmin(leave blank to disable admin)</param-value>\n</init-param>\n";
    public static void main(String[] args) throws Exception{
        int portNo = (args.length > 0 && Pattern.matches("^[0-9]+$", args[0]) ?  Integer.parseInt(args[0]) : 8080);
        String warPath = (args.length > 1 ? args[1] : "war");
        String webXmlFile = (args.length > 2 ? args[2] : "/WEB-INF/web.xml");

        System.out.println("java -classpath \"libs/*\" com.viloma.jagoframework.JagoServer [port] [war_folder] [web_xml_fullpath]  ");
        if(new File(warPath).isDirectory() && new File(warPath + webXmlFile).isFile()){
            startFromWar(portNo, warPath, webXmlFile);
        } else {
            System.out.println("JagoServer expect a /war folder with public files placed withing /war/public and config in /war/WEB-INF/web.xml");
            System.out.println("Sample web.xml contains \n<web-app>\n<servlet>\n<servlet-name>jago</servlet-name>\n<servlet-class>your.controller.classname</servlet-class>\n"+dbConfigSample+ adminConfigSample+
                    "</servlet><servlet-mapping>\n<servlet-name>jago</servlet-name>\n<url-pattern>/</url-pattern>\n</servlet-mapping>\n<servlet-mapping>\n<servlet-name>default</servlet-name>\n"+
                    "<url-pattern>/public/*</url-pattern>\n</servlet-mapping>\n<welcome-file-list>\n<welcome-file>index.html</welcome-file>\n</welcome-file-list>\n</web-app>");
        }
    }
    private static Object webappHandle;
    public static void startFromWar(int portNo, String warPath, String webXmlFile) throws Exception {
        org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(portNo);
        org.eclipse.jetty.webapp.WebAppContext webapp = new org.eclipse.jetty.webapp.WebAppContext();
        webappHandle = webapp;
        webapp.setContextPath("/");
        webapp.setResourceBase(warPath);
        webapp.setDescriptor(warPath+webXmlFile);
        webapp.setInitParameter("cacheControl", "max-age=0,public");
        webapp.setParentLoaderPriority(false);
        server.setHandler(webapp);

        server.start();
        System.out.println("Started server from "+ webXmlFile +" on port:" + portNo);
        server.join();
    }

    public static class OmUser{
        String userID, name, email, password;
    }
    public static class OmPage{

    }
    protected String encrypt(String message) throws Exception {
        java.security.MessageDigest md= java.security.MessageDigest.getInstance("SHA-512");
        md.update(message.getBytes());
        byte[] mb = md.digest();
        String out = "";
        for (int i = 0; i < mb.length; i++) {
            byte temp = mb[i];
            String s = Integer.toHexString(new Byte(temp));
            while (s.length() < 2) {
                s = "0" + s;
            }
            s = s.substring(s.length() - 2);
            out += s;
        }
        //System.out.println(out.length() +"CRYPTO: " + out);
        return out;
    }

    public void initCms() throws SQLException{
        getDb().linkClassToTable(OmUser.class, "OmUser", "userID", false);
    }
    public static class JagoServerPages extends JagoServer{
        private static final long serialVersionUID = 1L;
        @Override public void init(ServletConfig config){
            initDb(config.getInitParameter("DB"));
            initManager(config.getInitParameter("ADMIN_PAGE"));

        }
    }

}