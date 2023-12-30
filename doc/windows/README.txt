Download and install Java from
<https://java.com/en/download/manual.jsp>.

Extract the pulsar-vlds-<VERSION>-windows.zip archive to a location of
your choosing.

Next, create a lexicon for your tables. The lexicon should be a JSON
file containing an object with fields as follows:

  "Linking Col" (string, required)
    The name of the linking column.

  "DB1 Name" (string, required)
    The name of table 1 (the table of data owner 1).

  "DB2 Name" (string, required)
    The name of table 2 (the table of data owner 2).

  "DB1 Col" (array, required)
    An array of objects that specifies the columns of table 1.

  "DB2 Col" (array, required)
    An array of objects that specifies the columns of table 2.

Each object that specifies a column should have fields as follows:

  "Name" (string, required)
    The name of the column.

  "Type" (string, required)
    The type of the column. This must be either "decimal", "int", or
    "string".

  "Signed" (boolean, optional)
    Whether the type of the column is signed. This only applies to
    decimal and int columns. It is ignored for other column types.
    It defaults to true.

  "BitWidth" (number, required)
    This will be used in MPC, which is not yet implemented. You can
    set this to 1024 as a placeholder.

  "FracWidth" (number, required for decimal columns)
    This will be used in MPC, which is not yet implemented. You can
    set this to 1024 as a placeholder.

  "Domain" (array, required where it makes sense)
    An array of strings that specifies the set of possible values
    that this column may have.

Here is an example lexicon:

  {
    "Linking Col": "CaseID",
    "DB1 Name": "StudentDB",
    "DB2 Name": "IncomeDB",
    "DB1 Col": [
      {
        "Name": "CaseID",
        "Type": "int",
        "Signed": "false",
        "BitWidth": 34,
        "FracWidth": 0,
        "Domain": null
      },
      {
        "Name": "Degree",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "4",
        "FracWidth": "0",
        "Domain": ["1", "2", "3", "4", "5"]
      },
      {
        "Name": "School",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "4",
        "FracWidth": "0",
        "Domain": ["1", "2", "3", "4", "5"]
      },
      {
        "Name": "Sex",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "4",
        "FracWidth": "0",
        "Domain": ["1", "2", "3"]
      },
      {
        "Name": "GradYear",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "14",
        "FracWidth": "0",
        "Domain": null
      }
    ],
    "DB2 Col": [
      {
        "Name": "CaseID",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "34",
        "FracWidth": "0",
        "Domain": null
      },
      {
        "Name": "Income",
        "Type": "decimal",
        "Signed": "false",
        "BitWidth": "41",
        "FracWidth": "7",
        "Domain": null
      },
      {
        "Name": "Quart",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "4",
        "FracWidth": "0",
        "Domain": ["1", "2", "3", "4"]
      },
      {
        "Name": "Ann_Income",
        "Type": "decimal",
        "Signed": "false",
        "BitWidth": "41",
        "FracWidth": "7",
        "Domain": null
      },
      {
        "Name": "Inc_Year",
        "Type": "int",
        "Signed": "false",
        "BitWidth": "14",
        "FracWidth": "0",
        "Domain": null
      }
    ]
  }

Next, create the configuration files for the four parties.

The analyst should have a config file as follows. The only variable you
should need to change is lexicon_file, which should be a path to your
lexicon file that you created above:

  raw_listen_host = 0.0.0.0
  raw_listen_port = 19500
  raw_connect_host_1 = 127.0.0.1
  raw_connect_host_2 = 127.0.0.1
  raw_connect_port_1 = 19501
  raw_connect_port_2 = 19502
  local_party = 0
  http_listen_host = 0.0.0.0
  http_listen_port = 8099
  lexicon_file = Test1Lex.json

Data owner 1 should have a config file as follows. The only variables
you should need to change are sqlserver_*, mysql_*, and lexicon_file.
Please note that the mysql_* variables are in fact used when connecting
to the Microsoft SQL Server; we have simply not given them better names
yet.

  jdbc_subprotocol = sqlserver
  sqlserver_host = 127.0.0.1
  sqlserver_port = 11111
  mysql_username = root
  mysql_password = root
  mysql_database = Test1DB
  mysql_table = StudentDB
  raw_listen_host = 0.0.0.0
  raw_listen_port = 19501
  raw_connect_host_1 = 127.0.0.1
  raw_connect_host_2 = 127.0.0.1
  raw_connect_host_3 = 127.0.0.1
  raw_connect_port_1 = 19501
  raw_connect_port_2 = 19502
  raw_connect_port_3 = 19503
  local_party = 1
  lexicon_file = Test1Lex.json

Data owner 2 should have a config file as follows. Like data owner 1,
the only variables you should need to change are sqlserver_*, mysql_*,
and lexicon_file.

  jdbc_subprotocol = sqlserver
  sqlserver_host = 127.0.0.1
  sqlserver_port = 11111
  mysql_username = root
  mysql_password = root
  mysql_database = Test1DB
  mysql_table = IncomeDB
  raw_listen_host = 0.0.0.0
  raw_listen_port = 19502
  raw_connect_host_1 = 127.0.0.1
  raw_connect_host_2 = 127.0.0.1
  raw_connect_host_3 = 127.0.0.1
  raw_connect_port_1 = 19501
  raw_connect_port_2 = 19502
  raw_connect_port_3 = 19503
  local_party = 2
  lexicon_file = Test1Lex.json

The hybrid server should have a config file as follows. The only
variable you should need to change is lexicon_file.

  raw_listen_host = 0.0.0.0
  raw_listen_port = 19503
  local_party = 3
  lexicon_file = Test1Lex.json

WARNING: Trailing spaces in config files may give incorrect behavior.
Please be careful to avoid them.

Next, run the four parties as follows in four different command prompt
windows, altering the paths as necessary:

  pulsar_vlds_front_server.cmd --config analyst.cfg

  pulsar_vlds_server.cmd --config data-owner-1.cfg

  pulsar_vlds_server.cmd --config data-owner-2.cfg

  pulsar_vlds_server.cmd --config hybrid.cfg

Finally, open the web page at <http://127.0.0.1:8099> and perform
queries as desired.
