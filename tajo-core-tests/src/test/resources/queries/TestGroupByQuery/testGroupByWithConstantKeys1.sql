select 123 as key, count(1) as total from lineitem group by key order by key, total;