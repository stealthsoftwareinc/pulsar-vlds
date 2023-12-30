CREATE DATABASE IF NOT EXISTS Test6DB;
CREATE TABLE Test6DB.HotelTbl (
GuestID INT(7) UNSIGNED PRIMARY KEY NOT NULL,
Date CHAR(10),
Nights INT(4),
Price DECIMAL(9,2),
Points INT(10));

INSERT INTO Test6DB.HotelTbl (GuestID,Date,Nights,Price,Points) VALUES
(113,"2018-07-14",3,400.86,NULL),
(64390,"2019-09-19",2,319.65,12),
(2634,"2019-01-05",7,226.41,NULL),
(33446,"2019-05-01",5,931.09,85);