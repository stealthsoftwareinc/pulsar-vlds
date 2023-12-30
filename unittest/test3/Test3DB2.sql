CREATE DATABASE IF NOT EXISTS Test3DB;
CREATE TABLE Test3DB.IncomeTbl (
HouseID INT(9) UNSIGNED PRIMARY KEY NOT NULL,
Income DECIMAL(10,2),
Year INT(4),
Bracket CHAR(5));

INSERT INTO Test3DB.IncomeTbl (HouseID,Income,Year,Bracket) VALUES
(51647,147258.90,2010,"High"),
(664378,55527.18,2016,"Med"),
(3199672,78245.33,2019,"Med");
