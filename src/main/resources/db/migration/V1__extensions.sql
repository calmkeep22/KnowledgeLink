-- 벡터 검색(pgvector)과 키워드 유사도(pg_trgm). 이미지(pgvector/pgvector)에 포함되어 있어야 한다.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
