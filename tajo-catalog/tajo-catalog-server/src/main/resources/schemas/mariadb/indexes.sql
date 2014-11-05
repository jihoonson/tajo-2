CREATE TABLE INDEXES (
  INDEX_ID INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  DB_ID INT NOT NULL,
  TID INT NOT NULL,
  INDEX_NAME VARCHAR(128) NOT NULL,
  INDEX_TYPE CHAR(32) NOT NULL,
  PATH VARCHAR(4096) NOT NULL,
  COLUMN_NAMES VARCHAR(256) NOT NULL, -- array of column names
  DATA_TYPES VARCHAR(128) NOT NULL, -- array of column types
  ORDERS VARCHAR(128) NOT NULL, -- array of column orders
  NULL_ORDERS VARCHAR(128) NOT NULL, -- array of null orderings
  IS_UNIQUE BOOLEAN NOT NULL,
  IS_CLUSTERED BOOLEAN NOT NULL,
  PRIMARY KEY (INDEX_ID),
  FOREIGN KEY (DB_ID) REFERENCES DATABASES_ (DB_ID) ON DELETE CASCADE,
  FOREIGN KEY (TID) REFERENCES TABLES (TID) ON DELETE CASCADE,
  UNIQUE INDEX IDX_DB_ID_NAME (DB_ID, INDEX_NAME)
)