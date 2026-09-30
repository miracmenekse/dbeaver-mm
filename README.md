# dbeaver-mm

🇹🇷 **[Türkçe dokümantasyon → README.tr.md](README.tr.md)**

**dbeaver-mm** is a fork of [DBeaver Community](https://github.com/dbeaver/dbeaver) aimed at analysts who read and query
normalized databases all day. Its goal is to show what an ID *means* without writing a join, and to let you pick
values instead of looking them up in another tab.

Everything runs on the client side on top of DBeaver's own model (foreign keys, virtual foreign keys, virtual model
description columns). It needs no server component or PRO features and adds no new rule engine.

> Working branch: [`dbeaver-mm-v0.1-on-devel`](https://github.com/miracmenekse/dbeaver-mm/tree/dbeaver-mm-v0.1-on-devel), rebased on upstream `devel`.
> Fork changes in the code are marked with `// dbeaver-mm` comments.

## Features

### Data grid: IDs with meaning
- **FK dictionary labels.** A foreign key cell shows the referenced row's description next to the ID: `2 | MAIN_ORDER`.
- **Choose the label column.** A button in the FK column header picks which column of the referenced table is shown.
  The choice is stored in DBeaver's virtual model, so the grid, filter box and SQL picker stay in sync.
- **Cross-connection FKs.** Labels also resolve through virtual FKs into *another connection*
  (e.g. `test_db.orders.service_id → config_db.service`). These labels are painted teal so you can tell them apart.
- **In-cell value picker.** Dictionary FK cells get a dropdown. It opens a searchable Value / Description popup
  (server-side search) and writes the pick as a normal edit. The popup stays on screen near the bottom edge.
- **Group rows by column.** From the context menu or a header icon: rows are sorted by the column and each group is
  shaded in two alternating matte colors.

### Result filter box
- Typing `column =` lists values to choose from, with their labels. This works for dictionary FKs, cross-connection
  FKs and plain columns.
- The search runs **in the database**, not only in the first rows you fetched.
- An option restricts the list to the values present in the current result.
- A picked value replaces the typed search text. Quoted values with spaces keep being searched.

### SQL editor
- **Inline FK picker.** After `fk_column =` or `column IN (`, a popup lists the referenced table's rows (ID + label). It follows the
  real or virtual FK, also into another connection. A header button chooses the label column.
- **Connections bar.** A filterable multi-select bar at the top of the editor chooses which connections this script
  may run against.
- **Automatic connection.** The editor switches to the connection that holds the tables of the statement you are
  writing. Candidates are the connections in the bar, or the ones tagged *Use SQL auto connection*.
- **Table completion across connections.** After `from` / `join` / `into` / `update`, tables of all candidate
  connections are listed as `Table | Connection`. Accepting a table also switches the connection.
- **Connection short codes.** Give a connection a short code (*Set SQL short code…* in the navigator). Typing `<code>.`
  then lists only that connection's tables. The connections bar shows the codes.
- **Columns after WHERE.** Typing `WHERE` / `AND` / `OR` opens column completion for the statement's table, even
  when that table lives in another connection.

### Script tools
- **Recent SQL Scripts panel.** A docked view lists the 10 most recently saved scripts of the active project as cards:
  file name, first comment line and the statement's WHERE conditions. Favorites are pinned on top. One click opens
  the script.
- **Script parameter form.** A form lists every `column = value` condition of the script, pre-filled and editable,
  so a saved script can be re-parameterised in one place. It only rewrites the text and never runs the query.
  It opens when a script is launched from the panel, or with `Ctrl+Alt+P` in any SQL editor.

### Search and navigation
- **Accent- and case-blind search** in all pickers and the filter box. Turkish letters are folded, so `müşteri` finds
  `Musteri`.
- **Simpler navigator.** Tables are shown directly under a connection or schema. Views, indexes, sequences, triggers
  and other objects are collapsed into one *Other objects* node.

## Try it
The repository root has two SQLite scripts that set up the demo schemas used during development:
- `bsn_flow_spec_setup.sql`: a table with FKs to three dictionary tables.
- `cross_connection_fk_setup.sql`: data for a second connection, to test cross-connection virtual FKs.

Build it like upstream DBeaver (see [Building from sources](https://github.com/dbeaver/dbeaver/wiki/Build-from-sources)).
Close any running DBeaver before building.

---

*The upstream DBeaver README follows.*

[![Twitter URL](https://img.shields.io/twitter/url/https/twitter.com/dbeaver_news.svg?style=social&label=Follow%20%40dbeaver_news)](https://twitter.com/dbeaver_news)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/fa0bb9cf5a904c7d87424f8f6351ba92)](https://app.codacy.com/gh/dbeaver/dbeaver/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Apache 2.0](https://img.shields.io/github/license/cronn-de/jira-sync.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Tickets in review](https://img.shields.io/github/issues/dbeaver/dbeaver/wait%20for%20review)](https://github.com/dbeaver/dbeaver/issues?q=is%3Aissue+is%3Aopen+label%3A"wait%20for%20review")
<img src="https://github.com/dbeaver/dbeaver/wiki/images/dbeaver-icon-64x64.png" align="right"/>

# DBeaver

Free multi-platform database tool for developers, SQL programmers, database administrators and analysts.  

* Has a lot of <a href="https://github.com/dbeaver/dbeaver/wiki">features</a> including schema editor, SQL editor, data editor, AI chat, ER diagrams, data export/import/migration, SQL execution plans, database administration tools, database dashboards, Spatial data viewer, proxy and SSH tunnelling, custom database drivers editor, etc.
* Out of the box supports more than <a href="#supported-databases">100 database drivers</a>.
* Supports any database which has JDBC or ODBC driver (basically - almost all existing databases).
* Integrates AI tools for work with data, SQL and database structure

<a href="https://dbeaver.io/product/dbeaver-sql-editor.png"><img src="https://dbeaver.io/product/dbeaver-sql-editor.png" width="400"/></a>
<a href="https://dbeaver.io/product/dbeaver-gis-viewer.png"><img src="https://dbeaver.io/product/dbeaver-gis-viewer.png" width="400"/></a>
<a href="https://dbeaver.io/product/dbeaver-data-editor.png"><img src="https://dbeaver.io/product/dbeaver-data-editor.png" width="400"/></a>
<a href="https://dbeaver.io/product/dbeaver-erd.png"><img src="https://dbeaver.io/product/dbeaver-erd.png" width="400"/></a>

## Download

You can download prebuilt binaries from <a href="https://dbeaver.io/download" target="_blank">official website</a> or directly from <a href="https://github.com/dbeaver/dbeaver/releases">GitHub releases</a>.  
You can also download <a href="https://dbeaver.io/files/ea" target="_blank">Early Access</a> version. We publish daily.  

## Running

Just run an installer and then click on app icon. Or unzip an archive and run `dbeaver` from command line.  

Note: DBeaver needs Java to run. <a href="https://adoptium.net/temurin/releases/?package=jre" target="_blank">OpenJDK 25</a> is included in all DBeaver distributions.
You can change default JDK version by replacing directory `jre` in dbeaver installation folder.

## Documentation

* [Full product documentation](https://dbeaver.com/docs/dbeaver/)
* [WIKI](https://github.com/dbeaver/dbeaver/wiki)
* [Issue tracker](https://github.com/dbeaver/dbeaver/issues)
* [Building from sources](https://github.com/dbeaver/dbeaver/wiki/Build-from-sources)

## Architecture

- DBeaver is written mostly on Java. However, it also uses a set of native OS-specific components for desktop UI, high performance database drivers and networking.
- Basic frameworks:
  - [OSGI](https://en.wikipedia.org/wiki/OSGi) platform for plugins and dependency management. Community version consists of 130+ plugins.
  - [Eclipse RCP](https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/docs/Rich_Client_Platform.md) platform for rich user interface build.
  - [JDBC](https://en.wikipedia.org/wiki/Java_Database_Connectivity) for basic database connectivity API.
  - [JSQLParser](https://github.com/JSQLParser/JSqlParser) and [Antlr4](https://github.com/antlr/antlr4) for SQL grammar and semantic parser.
- For networking and additional functionality we use wide range of open source libraries such as [SSHJ](https://github.com/hierynomus/sshj), [Apache POI](https://github.com/apache/poi), [JFreeChart](https://github.com/jfree/jfreechart), [JTS](https://github.com/locationtech/jts), [Apache JEXL](https://github.com/apache/commons-jexl) etc.
- We separate model plugins from desktop UI plugins. This allows us to use the same set of "back-end" plugins in both DBeaver and [CloudBeaver](https://github.com/dbeaver/cloudbeaver).
- Dependencies: being an OSGI application we use P2 repositories for third party dependencies. For additional Maven dependencies we use our own [DBeaver P2 repo](https://github.com/dbeaver/dbeaver-deps-ce).

## Supported databases

### Community version

Out of the box DBeaver supports following database drivers:
- Altibase, Apache Calcite Avatica, Apache Doris, Apache Druid, Apache Hive, Apache Hive/Impala/Spark, Apache Ignite, Apache IoTDB, Apache Kylin, Apache Kyuubi, Apache Solr, Athena, Azure SQL, Babelfish, ClickHouse, Cloudberry, CockroachDB, CrateDB, CSV, CUBRID, Dameng, Data Virtuality, Databend, Databricks, DB2, DBF, Denodo, Derby, DolphinDB, Dremio, Drill, DuckDB, Elasticsearch, EnterpriseDB, Exasol, Firebird, Firebird, GaussDB, GBase 8s, GemFire XD, GizmoSQL, Google BigQuery, Google Cloud SQL for PostgreSQL, Google Spanner, Greengage, Greenplum, GreptimeDB, H2, H2GIS, HSQLDB, Informix, Ingres, InterSystems Caché, IRIS, JDBCX, Jennifer, Kingbase, LibSQL, Machbase, Manticore Search, MapD, MariaDB, Materialize, MaxDB, Mimer SQL, MonetDB, MS Access, MySQL, NDB Cluster, Netezza, NuoDB, OceanBase, Ocient, OmniSci, Open Distro Elasticsearch, OpenEdge, OpenSearch, Oracle, OrientDB, Pervasive SQL, Phoenix, PostgreSQL, Presto, Redshift, RisingWave, Salesforce, Salesforce Data 360, SAP HANA, SnappyData, Snowflake, SQL Server, SQLite, SQream DB, StarRocks, Sybase, TDEngine, Teiid, Teradata, TiDB, TiDBLake, TimechoDB, Timeplus, Timeplus Proton, TimescaleDB, Trino, Vertica, Virtuoso, WMI, Yellowbrick, Yugabyte.

### PRO versions

<a href="https://dbeaver.com/download/">Commercial versions</a> extends functionality of community drivers, supports NoSQL databases and many more:
- Amazon Aurora DSQL, Apache Arrow, AWS DocumentDB, AWS Keyspaces, AWS Neptune, AWS Timestream, Azure CosmosDB, BigTable, Cassandra, Couchbase, CouchDB, DynamoDB, etcd, FerretDB, Firestore, Fujitsu Enterprise Postgres, Google AlloyDB, Google Cloud SQL, InfluxDB, Kafka KSQL, Microsoft Fabric, MongoDB, Neo4j, NetSuite, ODBC, Raima, Redis, Salesforce, ScyllaDB, SingleStore, SQLite Crypt, Valkey, Yugabyte.
- Files as databases: CSV, DDL, JSON, Parquet, XLSX, and XML.
- Federated (multi-source) database based on Apache Calcite.

You can find the list of all databases supported in commercial versions <a href="https://dbeaver.com/databases/">here</a>.

## AI integration

- All DBeaver products contain AI Chat view similar to classic LLM chats. 
- You can generate/analyse/optimize your SQL queries, work with database structure or even work with databases with a very little knowledge of SQL.
- We use smart chat context which provides LLMs details about database structure, SQL dialect, etc. 
- LLM integration uses context-dependent dynamic tools and is very efficient from token consumption point of view.
- AI providers in Community version:
  - OpenAI (allows to configure most of existing LLMs with custom endpoint)
  - Copilot
- Pro versions provide additional AI tools + native support of Anthropic/Grok/Azure/Bedrock/Gemini/Ollama providers.

## Feedback

- For bug reports and feature requests - please <a href="https://github.com/dbeaver/dbeaver/issues">create a ticket</a>.
- To promote <a href="https://github.com/dbeaver/dbeaver/issues?q=is%3Aissue+is%3Aopen+sort%3Areactions-%2B1-desc+label%3A%22wait+for+votes%22">a ticket</a> to a higher priority - please vote for it with 👍 under the ticket description.
- If you have any questions, ideas, etc - please <a href="https://github.com/dbeaver/dbeaver/discussions">start a discussion</a>.
- Pull requests are welcome. See our <a href="https://github.com/dbeaver/dbeaver/wiki/Contribute-your-code">guide for contributors</a>.
- Visit https://dbeaver.com for more information.
- Follow us on [X](https://x.com/dbeaver_news/) and watch educational video on [YouTube](https://www.youtube.com/@DBeaver_video)
- Thanks for using DBeaver! Star if you like it.

## Contribution: help the Beaver!

Hooray, we have reached 50k+ stars on GitHub and continue to grow!  
That's really cool, and we are glad that you like DBeaver.

- We are actively looking for new source code contributors. We have added labels “Good first issue” and “Help wanted” to some tickets. If you want to be a part of our development team, just be brave and take a ticket. <a href="https://dbeaver.com/help-dbeaver/">We are happy to reward</a> our most active contributors every major sprint.
- You can buy <a href="https://dbeaver.com/buy/">one of our commercial versions</a>. They include NoSQL databases support, additional extensions, and official online support. Also, licensed users have priorities in bug fixes and the development of new features.

Thank you!  

- <a href="https://github.com/dbeaver/dbeaver/graphs/contributors">DBeaver Team</a> (contributors)

---------

## Our other open-source products:

- <a href="https://github.com/dbeaver/cloudbeaver">CloudBeaver</a> - web-based database management tool built on the DBeaver platform.<br/>Runs as server (docker) and provides rich web interface (SPA).  
- <a href="https://github.com/dbeaver/dbvr">dbvr</a> - CLI database management tool. Useful in CI/CD pipelines and all sort of automations. 

---

## dbeaver-mm development history

Each step of the fork, oldest first, so you can follow how the product grew. Items are numbered `K<n>` in commit messages.

| Step | Date | Change |
|---|---|---|
| [PR #1](https://github.com/miracmenekse/dbeaver-mm/pull/1) v0.1 | 2026-08-15 | First release. **FK dictionary labels** in the grid (`2 \| MAIN_ORDER`) with a header button that chooses the label column. New **Recent SQL Scripts** panel with favorites. New **inline FK picker** after `=` / `IN (` in the SQL editor, plus the **script parameter form** (`Ctrl+Alt+P`). |
| K1–K2 | 2026-09-19 | FK labels resolved through **cross-connection virtual FKs** (painted teal). `column =` in the result filter box lists dictionary values with labels. The SQL picker can choose its label column, shared with the grid. |
| K3–K6 | 2026-09-19 | **Group rows by column** in two alternating colors. **Simpler navigator** with an *Other objects* node. **In-cell FK dropdown** writes the picked value as an edit. The SQL editor **picks the connection automatically** from the statement's tables. |
| K7–K10 | 2026-09-20 | The filter box lists values of plain columns too. The cell picker becomes a **searchable popup**. **Column completion after WHERE / AND / OR** across connections. New **connections bar** on top of the SQL editor. |
| K11 | 2026-09-20 | **Table name completion across connections** after `from` / `join` / `into` / `update`. Accepting a table switches the connection. |
| K12–K13 | 2026-09-20 | **Accent- and case-blind search** (Turkish letters folded). No duplicated label when a column is its own label. **Connection short codes** (`<code>.` lists only that connection's tables). |
| K14 | 2026-09-22 | The SQL FK picker follows the real or virtual FK into another connection. The cell picker stays on screen near the bottom edge. |
| K15 | 2026-09-28 | A value picked in the filter box replaces the typed search text. |
| K16 | 2026-09-28 | The filter box value search runs **in the database**, not only in the first 50 rows. |
| K17 | 2026-09-28 | No `[NULL]` in dictionary labels. Accent-blind dictionary search is done in the database. |
| K18 | 2026-09-28 | Value search keeps going after a space inside an open quote. |
| K19 | 2026-09-28 | The connections bar shows each connection's short code. |
| K20 | 2026-09-30 | Option to list **only the values present in the result** in the filter box, labels included. |
