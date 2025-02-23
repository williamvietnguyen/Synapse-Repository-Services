SELECT 
 R.OBJECT_ID AS ID,
 SUM(CRC32(CONCAT(':salt','-',R.ETAG,'-',R.OBJECT_VERSION,'-',R.BENEFACTOR_ID))) AS CHECK_SUM
  FROM OBJECT_REPLICATION R
   WHERE %s
   GROUP BY R.OBJECT_ID
    ORDER BY R.OBJECT_ID, R.OBJECT_VERSION ASC
     LIMIT :limit OFFSET :offset