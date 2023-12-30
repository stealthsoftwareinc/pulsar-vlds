CREATE DATABASE IF NOT EXISTS Test3DB;
CREATE TABLE Test3DB.FamilyTbl (
HouseID INT(9) UNSIGNED PRIMARY KEY NOT NULL,
Occupants INT(3),
Zip INT(5),
Style CHAR(10));

INSERT INTO Test3DB.FamilyTbl (HouseID, Occupants,Zip,Style) VALUES
(1627248,2,12345,"Duplex"),
(51647,3,90210,"SF"),
(664378,1,72201,"Ranch"),
(3199672,3,50034,"Condo"),
(47835435,5,10004,"SF");
