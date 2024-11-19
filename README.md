![Bublik](/sql/bublik.png)

# Tool for Data Transfer from Oracle to PostgreSQL or from PostgreSQL to PostgreSQL

Using this tool, you can efficiently transfer data from Oracle to PostgreSQL or from PostgreSQL to PostgreSQL.

The quickest way to extract data from Oracle is using `ROWID`—employing `dbms_parallel_execute` to segment the data into chunks. 
In case of PostgreSQL, we should split a table into chunks by `CTID`.

The fastest way to input data into PostgreSQL is using the `COPY` command in binary format.

* [Oracle To PostgreSQL](#Oracle-To-PostgreSQL)
  * [Environment](#Environment)
  * [Config File](#Config-File)
  * [Mapping File](#Mapping-File)
  * [Create Oracle ROWID Chunks and Run](#Chunks-and-Run)
* [PostgreSQL To PostgreSQL](#PostgreSQL-To-PostgreSQL)
  * [Environment](#Environment-1)
  * [Config File](#Config-File-1)
  * [Mapping File](#Mapping-File-1)
  * [Create PostgreSQL CTID Chunks](#Create-PostgreSQL-CTID-Chunks)
* [PostgreSQL To Cassandra](#PostgreSQL-To-Cassandra)
  * [Environment](#Environment-2)
* [Usage](#Usage)
  * [As a Cli](#As-a-Cli)
  * [As a Service](#As-a-Service)

## Oracle To PostgreSQL

![Oracle To PostgreSQL](/sql/oracletopostgresql.png)

The goal is to migrate <strong>TABLE1</strong>, <strong>Table2</strong>, and <strong>PARTED</strong> tables from Oracle <strong>TEST<strong> schema to a PostgreSQL database.

**Supported types**

| ORACLE                   | Postgresql (possible types)                          |
|:-------------------------|:-----------------------------------------------------|
| char, varchar, varchar2  | char, bpchar, varchar, text, uuid                    |
| varchar2                 | jsonb                                                |
| CLOB                     | varchar, text, jsonb                                 |
| BLOB                     | bytea                                                |
| RAW                      | bytea                                                |
| date                     | date, timestamp, timestamptz                         |
| timestamp                | timestamp, timestamptz                               |
| timestamp with time zone | timestamptz                                          |
| number                   | numeric, smallint, bigint, integer, double precision |
| interval year to moth    | interval                                             |
| interval day to second   | interval                                             |

[Java Datatype Mappings](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjdbc/accessing-and-manipulating-Oracle-data.html#GUID-1AF80C90-DFE6-4A3E-A407-52E805726778)

### Environment

Use docker containers for all steps.

```
git clone https://github.com/dimarudik/bublik.git
cd bublik/
```

```
mvn -f bublik/pom.xml clean install -DskipTests
mvn -f cli/pom.xml clean package -DskipTests
```

#### Prepare Oracle Environment

<details><summary>arm64</summary>

```
docker run --name oracle \
-p 1521:1521 -p 5500:5500 \
   -e ORACLE_PWD=oracle_4U \
   -v ./dockerfiles/scripts:/docker-entrypoint-initdb.d \
   -d dimarudik/oracle_arm64:19.3.0-ee
```

</details>

<details><summary>x86_64</summary>

```
docker run --name oracle \
-p 1521:1521 -p 5500:5500 \
   -e ORACLE_PWD=oracle_4U \
   -v ./dockerfiles/scripts:/docker-entrypoint-initdb.d \
   -d dimarudik/oracle_x86_64:19.3.0-ee
```

</details>
  
> [!NOTE] 
> `TABLE1`, `Table2`, and `PARTED` tables will be created and fulfilled during Oracle docker container startup.

To connect to Oracle, use a command:

```
sqlplus 'test/test@(description=(address=(host=localhost)(protocol=tcp)(port=1521))(connect_data=(service_name=ORCLPDB1)))'
```

[How to install Oracle Instant Client](https://www.oracle.com/database/technologies/instant-client.html)

#### Prepare PostgreSQL Environment

<details><summary>Query</summary>

```
docker run --name postgres \
        -h postgres \
        -e POSTGRES_USER=postgres \
        -e POSTGRES_PASSWORD=postgres \
        -e POSTGRES_DB=postgres \
        -p 5432:5432 \
        -v ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql \
        -v ./sql/.psqlrc:/var/lib/postgresql/.psqlrc \
        -v ./sql/bublik.png:/var/lib/postgresql/bublik.png \
        -d postgres \
        -c shared_preload_libraries="pg_stat_statements,auto_explain" \
        -c timezone="+03" \
        -c max_connections=200 \
        -c logging_collector=on \
        -c log_directory=pg_log \
        -c log_filename=%u_%a.log \
        -c log_min_duration_statement=3 \
        -c log_statement=all \
        -c auto_explain.log_min_duration=0 \
        -c auto_explain.log_analyze=true
```
</details>

> [!NOTE] 
> Tables `public.table1`, `public.table2`, `public.parted` will be created during Postgre docker container startup.

To connect to PostgreSQL, use a command:

```
psql postgresql://test:test@localhost/postgres
```

### Config File

<details><summary>./cli/config/ora2pg.yaml</summary>

```yaml
threadCount: 10

fromProperties:
  url: jdbc:oracle:thin:@(description=(address=(host=localhost)(protocol=tcp)(port=1521))(connect_data=(service_name=ORCLPDB1)))
  user: test
  password: test
toProperties:
  url: jdbc:postgresql://localhost:5432/postgres
  user: test
  password: test
```

</details>


### Mapping File

<details><summary>./cli/config/ora2pg.json</summary>

```json
[
  {
    "fromSchemaName" : "TEST",
    "fromTableName" : "TABLE1",
    "fromTableAlias" : "t",
    "fromTableAdds" : "left join test.currencies c on t.currency_id = c.id",
    "toSchemaName" : "PUBLIC",
    "toTableName" : "TABLE1",
    "fetchHintClause" : "/*+ no_index(T) */",
    "fetchWhereClause" : "1 = 1",
    "fromTaskName" : "TABLE1_TASK",
    "fromTaskWhereClause" : " 1 = 1 ",
    "tryCharIfAny" : ["current_mood"],
    "columnToColumn" : {
      "\"LEVEL\""         : "level",
      "create_at"         : "create_at",
      "update_at"         : "update_at",
      "gender"            : "gender",
      "byteablob"         : "byteablob",
      "textclob"          : "textclob",
      "\"CaseSensitive\"" : "\"CaseSensitive\"",
      "rawbytea"          : "rawbytea",
      "doc"               : "doc",
      "uuid"              : "uuid",
      "clobjsonb"         : "clobjsonb",
      "current_mood"      : "current_mood"
    },
    "expressionToColumn" : {
      "t.id as id" : "id",
      "c.name as currency_name" : "currency_name",
      "(select name from test.countries c where c.id = t.country_id) as country_name" : "country_name"
    }
  },
  {
    "fromSchemaName" : "TEST",
    "fromTableName" : "\"Table2\"",
    "toSchemaName" : "PUBLIC",
    "toTableName" : "\"TABLE2\"",
    "fetchHintClause" : "/*+ no_index(TABLE2) */",
    "fetchWhereClause" : "1 = 1",
    "fromTaskName" : "TABLE2_TASK",
    "columnToColumn" : {
      "id"          : "id",
      "\"LEVEL\""   : "level",
      "create_at"   : "create_at",
      "update_at"   : "update_at",
      "gender"      : "gender",
      "byteablob"   : "byteablob",
      "textclob"    : "textclob"
    },
    "columnFromMany" : {
       "tstzrange" : ["create_at", "update_at"]
    }
  },
  {
    "fromSchemaName" : "TEST",
    "fromTableName" : "PARTED",
    "toSchemaName" : "PUBLIC",
    "toTableName" : "PARTED",
    "fetchHintClause" : "/*+ no_index(PARTED) */",
    "fetchWhereClause" : "create_at >= to_date('2022-01-01','YYYY-MM-DD') and create_at <= to_date('2023-12-31','YYYY-MM-DD')",
    "fromTaskName" : "PARTED_TASK",
    "fromTaskWhereClause" : "(DBMS_ROWID.ROWID_OBJECT(START_ROWID) IN ((select DBMS_ROWID.ROWID_OBJECT(rowid) object_id from test.parted partition for (to_date('20220101', 'YYYYMMDD')) where rownum = 1), (select DBMS_ROWID.ROWID_OBJECT(rowid) object_id from test.parted partition for (to_date('20230101', 'YYYYMMDD')) where rownum = 1)) OR DBMS_ROWID.ROWID_OBJECT(END_ROWID) IN ((select DBMS_ROWID.ROWID_OBJECT(rowid) object_id from test.parted partition for (to_date('20220101', 'YYYYMMDD')) where rownum = 1),(select DBMS_ROWID.ROWID_OBJECT(rowid) object_id from test.parted partition for (to_date('20230101', 'YYYYMMDD')) where rownum = 1)))",
    "columnToColumn" : {
      "id"        : "id",
      "create_at" : "create_at",
      "name"      : "name"
    }
  }
]
```

</details>

> [!IMPORTANT]
> The case-sensitive or reserved words must be quoted with double quotation and backslashes.  

> [!NOTE]
> To enrich data from other tables, you can use combination of **fromTableAlias**, **fromTableAdds**, and **expressionToColumn** definitions.

For example, we can get data from `TABLE1` by using the following query:

```
 SELECT /* bublik */ /*+ no_index(T) */
    "LEVEL",
    create_at,
    update_at,
    gender,
    byteablob,
    textclob,
    "CaseSensitive",
    rawbytea,
    doc,
    uuid,
    clobjsonb,
    current_mood,
    t.id as id,
    c.name as currency_name,
    (select name from test.countries c where c.id = t.country_id) as country_name
  FROM TEST.TABLE1 t left join test.currencies c
    on t.currency_id = c.id WHERE 1 = 1 and t.rowid between ? and ?
```

> [!NOTE]
> To speed up the chunk processing of partitioned table, you can use **fromTaskWhereClause** clause as it used above.
> It allows to exclude excessive workload.

> [!NOTE]
> If the target column type doesn't support by tool, you can try to use Character  
> by using declaration of column's name in **tryCharIfAny** array.
 
### Create Oracle ROWID Chunks and Run

Halt any changes to the movable tables in the source database (Oracle).

You can create chunks automatically using `-k` parameter at startup:

```
java \
  -jar ./cli/target/bublik-cli-1.2.1.jar \
  -k 200000 \
  -c ./cli/config/ora2pg.yaml \
  -m ./cli/config/ora2pg.json
```

> [!NOTE]
> If the migration was interrupted due to any infrastructure issues, you can resume the process without `-k` parameter—in this case
unprocessed data chunks will be transferred. 

You can prepare data chunks in Oracle manually by using the same user credentials specified in `fromProperties` key in `./cli/config/ora2pg.yaml`:

```
exec dbms_parallel_execute.drop_task(task_name => 'TABLE1_TASK');
exec dbms_parallel_execute.create_task (task_name => 'TABLE1_TASK');
begin
    dbms_parallel_execute.create_chunks_by_rowid (  task_name   => 'TABLE1_TASK',
                                                    table_owner => 'TEST',
                                                    table_name  => 'TABLE1',
                                                    by_row => TRUE,
                                                    chunk_size  => 100000 );
end;
/
exec dbms_parallel_execute.drop_task(task_name => 'TABLE2_TASK');
exec dbms_parallel_execute.create_task (task_name => 'TABLE2_TASK');
begin
    dbms_parallel_execute.create_chunks_by_rowid (  task_name   => 'TABLE2_TASK',
                                                    table_owner => 'TEST',
                                                    table_name  => 'Table2',
                                                    by_row => TRUE,
                                                    chunk_size  => 100000 );
end;
/
exec dbms_parallel_execute.drop_task(task_name => 'PARTED_TASK');
exec dbms_parallel_execute.create_task(task_name => 'PARTED_TASK');
begin
    dbms_parallel_execute.create_chunks_by_rowid (  task_name   => 'PARTED_TASK',
                                                    table_owner => 'TEST',
                                                    table_name  => 'PARTED',
                                                    by_row => TRUE,
                                                    chunk_size  => 20000 );
end;
/
```

## PostgreSQL To PostgreSQL

![PostgreSQL To PostgreSQL](/sql/PostgreSQLToPostgreSQL.png)

The goal is to migrate <strong>Source</strong> table to <strong>Target</strong> table from one PostgreSQL database to another. To simplify test case we're using same database.

### Environment

Use docker containers for all steps.

```
git clone https://github.com/dimarudik/bublik.git
cd bublik/
```

```
mvn -f bublik/pom.xml clean install -DskipTests
mvn -f cli/pom.xml clean package -DskipTests
```

```
docker run --name postgres \
        -e POSTGRES_USER=postgres \
        -e POSTGRES_PASSWORD=postgres \
        -e POSTGRES_DB=postgres \
        -p 5432:5432 \
        -v ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql \
        -v ./sql/.psqlrc:/var/lib/postgresql/.psqlrc \
        -v ./sql/bublik.png:/var/lib/postgresql/bublik.png \
        -d postgres \
        -c shared_preload_libraries="pg_stat_statements,auto_explain" \
        -c max_connections=200 \
        -c logging_collector=on \
        -c log_directory=pg_log \
        -c log_filename=%u_%a.log \
        -c log_min_duration_statement=3 \
        -c log_statement=all \
        -c auto_explain.log_min_duration=0 \
        -c auto_explain.log_analyze=true
```

> [!IMPORTANT]
> `SOURCE` and `TARGET` tables will be created during postgre docker container startup.

To connect, use a command:

```
psql postgresql://test:test@localhost/postgres
```

### Config File

<details><summary>yaml</summary>

```yaml
threadCount: 10

fromProperties:
  url: jdbc:postgresql://localhost:5432/postgres?options=-c%20enable_indexscan=off%20-c%20enable_indexonlyscan=off%20-c%20enable_bitmapscan=off
  user: test
  password: test
toProperties:
  url: jdbc:postgresql://localhost:5432/postgres
  user: test
  password: test
```

</details>

### Mapping File

<details><summary>json</summary>

```json
[
  {
    "fromSchemaName" : "PUBLIC",
    "fromTableName" : "\"Source\"",
    "toSchemaName" : "PUBLIC",
    "toTableName" : "TARGET",
    "fetchWhereClause" : "1 = 1",
    "fromTaskName" : "TABLE1_TASK",
    "tryCharIfAny" : ["current_mood", "gender"],
    "columnToColumn" : {
      "id"            : "id",
      "uuid"          : "uuid",
      "\"Primary\""   : "\"Primary\"",
      "boolean"       : "boolean",
      "int2"          : "int2",
      "int4"          : "int4",
      "int8"          : "int8",
      "smallint"      : "smallint",
      "bigint"        : "bigint",
      "numeric"       : "numeric",
      "float8"        : "float8",
      "date"          : "date",
      "timestamp"     : "timestamp",
      "timestamptz"   : "timestamptz",
      "description"   : "rem",
      "image"         : "image",
      "current_mood"  : "current_mood"
    },
    "expressionToColumn" : {
      "(select 'male') as gender" : "gender"
    }
  }
]
```
</details>

> [!IMPORTANT]
> The case-sensitive or reserved words must be quoted with double quotation and backslashes.

> [!NOTE]
> **expressionToColumn** might be used for declaration of subquery for data enrichment.

> [!NOTE]
> If the target column type doesn't supported by tool, you can try to use Character  
> by using declaration of column's name in **tryCharIfAny** array.

### Create PostgreSQL CTID Chunks

To start the data transferring from source to target, Bublik prepares the CTID table at the source side:

```
create table if not exists public.ctid_chunks (
    chunk_id int generated always as identity primary key,
    start_page bigint,
    end_page bigint,
    task_name varchar(128),
    status varchar(20)  default 'UNASSIGNED',
    unique (start_page, end_page, task_name, status));
```

> [!NOTE]
> If you run bublik-cli with `-k` option, the CTID table will be created and fulfilled automatically.

## PostgreSQL To Cassandra

![Cassandra](/sql/cassandra4.png)

[Java Datatype Mappings](https://documentation.softwareag.com/webmethods/adapters_estandards/Adapters/Apache_Cassandra/Apache_for_Cassandra_10-2/10-2-0_Apache_Cassandra_webhelp/index.html#page/cassandra-webhelp/co-cql_data_type_to_jdbc_data_type.html)

### Environment

1. Step 1:

```shell
docker network create \
  --driver=bridge \
  --subnet=172.28.0.0/16 \
  --gateway=172.28.5.254 \
  bublik-network
```

2. Step 2:

```shell
docker run \
        --name postgres \
        --ip 172.28.0.7 \
        -h postgres \
        --network bublik-network \
        -e POSTGRES_USER=postgres \
        -e POSTGRES_PASSWORD=postgres \
        -e POSTGRES_DB=postgres \
        -p 5432:5432 \
        -v ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql \
        -v ./sql/.psqlrc:/var/lib/postgresql/.psqlrc \
        -v ./sql/bublik.png:/var/lib/postgresql/bublik.png \
        -d postgres \
        -c shared_preload_libraries="pg_stat_statements,auto_explain" \
        -c timezone="+03" \
        -c max_connections=200 \
        -c logging_collector=on \
        -c log_directory=pg_log \
        -c log_filename=%u_%a.log \
        -c log_min_duration_statement=3 \
        -c log_statement=all \
        -c auto_explain.log_min_duration=0 \
        -c auto_explain.log_analyze=true
```

3. Step 3:

```shell
docker build ./dockerfiles/cs1 -t cs1 ; \
docker build ./dockerfiles/cs2 -t cs2 ; \
docker build ./dockerfiles/cs3 -t cs3 ; \
docker build ./dockerfiles/cs4 -t cs4 ; \
docker build ./dockerfiles/cs5 -t cs5 ; \
docker build ./dockerfiles/cs6 -t cs6
```

4. Step 4:

```shell
docker run -d -h cs1 --ip 172.28.0.1 --name cs1 --network bublik-network -p 9042:9042 cs1 ; \
sleep 15; docker run -d -h cs2 --ip 172.28.0.2 --name cs2 --network bublik-network cs2 ; \
sleep 45; docker run -d -h cs3 --ip 172.28.0.3 --name cs3 --network bublik-network cs3 ; \
sleep 45; docker run -d -h cs4 --ip 172.28.0.4 --name cs4 --network bublik-network cs4 ; \
sleep 45; docker run -d -h cs5 --ip 172.28.0.5 --name cs5 --network bublik-network cs5 ; \
sleep 45; docker run -d -h cs6 --ip 172.28.0.6 --name cs6 --network bublik-network cs6
```

> [!IMPORTANT]
> Wait until all nodes start. If a node fails to start, remove it using
> `docker exec cs1 nodetool assassinate IP` command and re-create the broken container.

To check the node status, you can use nodetool like this:

```shell
docker exec cs1 nodetool status
# or
docker exec cs1 nodetool describecluster
```

5. Adjust the ``batch_size_fail_threshold_in_kb`` parameter:

```shell
docker exec -it cs1 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024 ; \
docker exec -it cs2 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024 ; \
docker exec -it cs3 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024 ; \
docker exec -it cs4 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024 ; \
docker exec -it cs5 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024 ; \
docker exec -it cs6 nodetool sjk mx -ms -b org.apache.cassandra.db:type=StorageService -f BatchSizeFailureThreshold -v 1024
```

6. Prepare the Keyspace and tables:

```shell
cqlsh -u cassandra -p cassandra -f ./sql/data.cql
```

```shell
docker exec -it cs1 nodetool repair ; \
docker exec -it cs2 nodetool repair ; \
docker exec -it cs3 nodetool repair ; \
docker exec -it cs4 nodetool repair ; \
docker exec -it cs5 nodetool repair ; \
docker exec -it cs6 nodetool repair
```

```shell
mvn -f bublik/pom.xml clean install -DskipTests ; \ 
mvn -f cli/pom.xml clean package -DskipTests ; \
psql postgresql://test:test@localhost/postgres -c "drop table ctid_chunks" ; \
docker rm cli -f ; \
docker image rm cli ; \
docker rmi $(docker images -f "dangling=true" -q) ; \
docker volume prune -f ; \
docker build --no-cache -t cli . ; \
docker run -h cli --network bublik-network --name cli cli:latest
```

## Usage

![Bublik](/sql/bublik.png)

You can use Bublik library as a part of cli utility or as a part of service.

Before usage, build the jar and put it in a local maven repository:

```shell
cd ./bublik
mvn clean install -DskipTests
```

### As a Cli

1. Build the cli:

```shell
cd ./cli
mvn clean package -DskipTests
```

2. Halt any changes to the movable tables in the source database.

3. Run the cli.

    - Oracle:
    
    ```
    java -jar ./target/bublik-cli-1.2.1.jar -c ./config/ora2pg.yaml -m ./config/ora2pg.json
    ```

    - PostgreSQL:
    
    ```
    java -jar ./target/bublik-cli-1.2.1.jar -c ./config/pg2pg.yaml -m ./config/pg2pg.json
    ```

   To prevent heap pressure, use `-Xmx16g`.

   Monitor the logs at `logs/app.log`.

4. Track the progress.

    - In Oracle:

    ```
    select status, count(*), round(100 / sum(count(*)) over() * count(*),2) pct 
        from user_parallel_execute_chunks group by status;
    ```

    - In PostgreSQL:

    ```
    select status, count(*), round(100 / sum(count(*)) over() * count(*),2) pct 
        from ctid_chunks group by status;
    ```

### As a Service

1. Build the service:

```shell
cd ./service
./gradlew clean build -x test
```

2. Halt any changes to the movable tables in the source database.

3. Run the service:

```
java -jar ./build/libs/service-1.2.1.jar
```

4. Consume the service:

```shell
newman run ./postman/postman_collection.json
```
