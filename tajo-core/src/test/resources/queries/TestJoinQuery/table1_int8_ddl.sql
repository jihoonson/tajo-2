create external table ${0} (id bigint, name text, score float, type text) using csv
with ('csvfile.delimiter'='|', 'csvfile.null'='NULL') location ${table.path};

