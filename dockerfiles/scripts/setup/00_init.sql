alter session set container = XEPDB1;
create user test identified by test;
alter user test quota unlimited on users;
grant connect, resource to test;
create table test.table1 (
    id number(19,0),
    name varchar2(255),
    create_at timestamp(6) with time zone,
    update_at timestamp(6) with time zone,
    gender number(1,0) check (gender in (0,1)),
    byteablob blob,
    textclob clob,
    exclude_me int,
    "CaseSensitive" varchar2(20),
    country_id int,
    primary key (id)
);
create table test.countries (
    id int,
    name varchar2(256) not null,
    primary key (id)
);
insert into test.countries values (1, 'Brazil');
insert into test.countries values (2, 'China');
insert into test.countries values (3, 'Egypt');
insert into test.countries values (4, 'Ethiopia');
insert into test.countries values (5, 'India');
insert into test.countries values (6, 'Iran');
insert into test.countries values (7, 'Russia');
insert into test.countries values (8, 'South Africa');
insert into test.countries values (9, 'United Arab Emirates');
commit;
insert into test.table1
    (select
        rownum as id,
        'Hi, I''m using varchar2 to varchar' as name,
        sysdate as create_at,
        systimestamp as update_at,
        mod(rownum, 2) as gender,
        utl_raw.cast_to_raw('Hi, I''m using CLOB to bytea') as byteablob,
        to_clob('Hi, I''m using CLOB to text') as textclob,
        null as exclude_me,
        null as "CaseSensitive",
        decode(round(dbms_random.value(0,9)),0,null,round(dbms_random.value(1,9))) as country_id
    from dual connect by level < 1000000);
commit;
create table test."Table2" as select * from test.table1;
