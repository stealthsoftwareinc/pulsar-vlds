CREATE DATABASE IF NOT EXISTS Test4DB;
CREATE TABLE Test4DB.HospitalTbl (
PatientID CHAR(10) PRIMARY KEY NOT NULL,
Days INT(9),
FName CHAR(20),
LName CHAR(20),
DOB CHAR(10),
Wt INT(4));

INSERT INTO Test4DB.HospitalTbl (PatientID,Days,FName,LName,DOB,Wt) VALUES
("A001-251",26,"Alice","Smith","1990-01-09",106),
("A003-644",1,"Bob","Jones","1970-11-21",123),
("B672-428",5,"Charlie","Alt","1987-07-16",151),
("R252-879",5,"Daisy","Rodriguez","2000-03-12",130),
("A575-183",12,"Eliza","Corwin","1954-05-05",146);


