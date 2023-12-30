CREATE DATABASE IF NOT EXISTS Test5DB;
CREATE TABLE Test5DB.StudentDB (
Degree INT(1),
School INT(1),
Sex INT(1),
CaseID INT(10) PRIMARY KEY NOT NULL,
GradYear INT(4));

INSERT INTO Test5DB.StudentDB (Degree,School,Sex,CaseID,GradYear) VALUES
(3,3,0,117,2000),
(2,2,1,290,2000),
(1,2,1,626,2000),
(1,1,1,635,2000),
(1,1,0,1570,2000),
(5,3,0,2856,2000);
