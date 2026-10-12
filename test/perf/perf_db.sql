-- dbeaver-mm: freeze test data, ~1 GB in 3 PostgreSQL databases (mm_main, mm_config, mm_archive).
-- Run: psql -h 127.0.0.1 -U mm -d postgres -f test/perf/perf_db.sql   (re-runnable, drops the DBs)
-- Deliberately dirty where the fork's FK labels are weak: orphan FK values (NOT VALID constraints),
-- dictionary rows with NULL names, a 45-column table, a 50k-row dictionary with an unindexed name,
-- one schema with 300+ tables.
\set ON_ERROR_STOP on
DROP DATABASE IF EXISTS mm_main;
DROP DATABASE IF EXISTS mm_config;
DROP DATABASE IF EXISTS mm_archive;
CREATE DATABASE mm_main;
CREATE DATABASE mm_config;
CREATE DATABASE mm_archive;

-- ── mm_config: dictionaries referenced from mm_main through virtual (cross-connection) FKs
\c mm_config
CREATE TABLE service_spec (id int PRIMARY KEY, code text NOT NULL, name text);
INSERT INTO service_spec SELECT g, 'SRV_' || g, 'Servis ' || g || ' Şirket İçi Çağrı' FROM generate_series(1, 200) g;
CREATE TABLE channel_spec (id int PRIMARY KEY, code text NOT NULL, name text);
INSERT INTO channel_spec VALUES (1,'WEB','Web Portal'),(2,'MOBILE','Mobil Uygulama'),(3,'CC','Çağrı Merkezi'),(4,'STORE','Mağaza');
DO $$ BEGIN
  FOR i IN 1..40 LOOP
    EXECUTE format('CREATE TABLE cfg_dict_%s (id int PRIMARY KEY, code text NOT NULL, name text, created_at timestamp DEFAULT now())', lpad(i::text, 2, '0'));
    EXECUTE format($q$INSERT INTO cfg_dict_%s (id, code, name) SELECT g, 'C%s_' || g, 'Yapılandırma %s değer ' || g FROM generate_series(1, %s) g$q$,
                   lpad(i::text, 2, '0'), i, i, 5 + i * 10);
  END LOOP;
END $$;

-- ── mm_main: the work DB look-alike
\c mm_main
CREATE SCHEMA domain_config;
CREATE SCHEMA pcm;
CREATE SCHEMA ntf_engine;
CREATE SCHEMA crm_customer_information;

-- 10 dictionaries per schema (5..500 rows); every 7th name is NULL
DO $$ DECLARE s text; BEGIN
  FOREACH s IN ARRAY ARRAY['domain_config','pcm','ntf_engine','crm_customer_information'] LOOP
    FOR i IN 1..10 LOOP
      EXECUTE format('CREATE TABLE %I.dict_%s (id int PRIMARY KEY, code text NOT NULL, name text, description text)', s, i);
      EXECUTE format($q$INSERT INTO %I.dict_%s SELECT g, upper(left(%L, 3)) || '_%s_' || g,
                       CASE WHEN g %% 7 = 0 THEN NULL ELSE 'Değer ' || g || ' – ığüşöç' END, md5(g::text)
                       FROM generate_series(1, %s) g$q$, s, i, s, i, (ARRAY[5,10,20,50,100,150,200,300,400,500])[i]);
    END LOOP;
  END LOOP;
END $$;

-- 20 mid tables per schema (10k..100k rows), 3 FKs each
DO $$ DECLARE s text; n int; BEGIN
  FOREACH s IN ARRAY ARRAY['domain_config','pcm','ntf_engine','crm_customer_information'] LOOP
    FOR i IN 1..20 LOOP
      n := 10000 + (i * 4500);
      EXECUTE format('CREATE TABLE %I.entity_%s (id bigint PRIMARY KEY, label text, type_id int REFERENCES %I.dict_1, status_id int REFERENCES %I.dict_3, prev_status_id int REFERENCES %I.dict_3, amount numeric(12,2), updated_at timestamp)', s, i, s, s, s);
      EXECUTE format($q$INSERT INTO %I.entity_%s SELECT g, 'kayıt ' || g, 1 + g %% 5, 1 + g %% 20,
                       CASE WHEN g %% 9 = 0 THEN NULL ELSE 1 + (g * 3) %% 20 END, (g %% 10000) / 7.0, now() - g * interval '1 min'
                       FROM generate_series(1, %s) g$q$, s, i, n);
    END LOOP;
  END LOOP;
END $$;

-- pcm: 300 small spec tables (navigator "Tables flattened")
DO $$ BEGIN
  FOR i IN 1..300 LOOP
    EXECUTE format('CREATE TABLE pcm.spec_%s (id int PRIMARY KEY, code text, kind_id int REFERENCES pcm.dict_2, note text)', lpad(i::text, 3, '0'));
    EXECUTE format($q$INSERT INTO pcm.spec_%s SELECT g, 'SP%s_' || g, 1 + g %% 10, 'not ' || g FROM generate_series(1, 20) g$q$, lpad(i::text, 3, '0'), i);
  END LOOP;
END $$;

-- crm: 50k-row dictionary, name unindexed, Turkish text
CREATE TABLE crm_customer_information.city_district (id int PRIMARY KEY, code text, name text);
INSERT INTO crm_customer_information.city_district
  SELECT g, 'D' || g, (ARRAY['İstanbul','Şanlıurfa','Çorum','Muğla','Iğdır','Düzce','Ağrı','Gümüşhane'])[1 + g % 8] || ' ilçe ' || g
  FROM generate_series(1, 50000) g;

-- pcm.product_offer: big (2.5M rows, 22 cols, 10 FK cols). Orphans in legacy_status_id/region_id,
-- added as NOT VALID so PostgreSQL still reports the FK.
CREATE TABLE pcm.product_offer (
  id bigint PRIMARY KEY, code text, name text,
  type_id int REFERENCES pcm.dict_1, status_id int REFERENCES pcm.dict_3, prev_status_id int REFERENCES pcm.dict_3,
  category_id int REFERENCES pcm.dict_5, owner_dept_id int REFERENCES domain_config.dict_4, target_dept_id int REFERENCES domain_config.dict_4,
  district_id int REFERENCES crm_customer_information.city_district,
  legacy_status_id int, region_id int,
  service_id int, channel_id int,           -- virtual FKs into mm_config
  price numeric(12,2), discount numeric(5,2), valid_from date, valid_to date,
  is_active boolean, created_by text, created_at timestamp, note text);
INSERT INTO pcm.product_offer
  SELECT g, 'PO' || g, 'Ürün teklifi ' || g,
    1 + g % 5, 1 + g % 20, CASE WHEN g % 11 = 0 THEN NULL ELSE 1 + (g * 7) % 20 END,
    1 + g % 100, 1 + g % 50, 1 + (g * 3) % 50,
    1 + (g * 13) % 50000,
    1 + g % 25, 1 + g % 120,                -- dict_3 has 20 rows, dict_6 150 → orphans in legacy_status_id
    1 + g % 210, 1 + g % 5,                 -- 201..210 and 5 are orphans in mm_config
    (g % 100000) / 3.0, (g % 50) / 2.0, date '2024-01-01' + g % 700, date '2026-01-01' + g % 700,
    g % 3 <> 0, 'user' || g % 40, now() - g * interval '10 s', CASE WHEN g % 4 = 0 THEN NULL ELSE 'açıklama ' || g END
  FROM generate_series(1, 2500000) g;
ALTER TABLE pcm.product_offer ADD FOREIGN KEY (legacy_status_id) REFERENCES pcm.dict_3 NOT VALID;
ALTER TABLE pcm.product_offer ADD FOREIGN KEY (region_id) REFERENCES pcm.dict_6 NOT VALID;

-- crm.customer_order: big (1.5M rows), FKs into other schemas
CREATE TABLE crm_customer_information.customer_order (
  id bigint PRIMARY KEY, offer_id bigint REFERENCES pcm.product_offer,
  order_type_id int REFERENCES crm_customer_information.dict_1, status_id int REFERENCES crm_customer_information.dict_3,
  district_id int REFERENCES crm_customer_information.city_district, ntf_template_id int REFERENCES ntf_engine.dict_8,
  channel_id int, qty int, total numeric(14,2), ordered_at timestamp);
INSERT INTO crm_customer_information.customer_order
  SELECT g, 1 + (g * 17) % 2500000, 1 + g % 5, 1 + g % 20, 1 + (g * 31) % 50000, 1 + g % 300, 1 + g % 4, 1 + g % 9, g % 9999, now() - g * interval '30 s'
  FROM generate_series(1, 1500000) g;

-- ntf_engine.wide_event: 45 columns, 8 FKs, 50k rows (horizontal scroll, header paint)
DO $$ DECLARE cols text := ''; vals text := ''; BEGIN
  FOR i IN 1..8 LOOP
    cols := cols || format(', fk%s_id int REFERENCES ntf_engine.dict_%s', i, i);
    vals := vals || format(', 1 + g %% %s', (ARRAY[5,10,20,50,100,150,200,300])[i]);
  END LOOP;
  FOR i IN 1..36 LOOP
    cols := cols || format(', attr_%s text', i);
    vals := vals || format($v$, 'v%s_' || g$v$, i);
  END LOOP;
  EXECUTE 'CREATE TABLE ntf_engine.wide_event (id bigint PRIMARY KEY' || cols || ')';
  EXECUTE 'INSERT INTO ntf_engine.wide_event SELECT g' || vals || ' FROM generate_series(1, 50000) g';
END $$;
VACUUM ANALYZE;

-- ── mm_archive: same table names as mm_main (table completion, short codes), small
\c mm_archive
CREATE SCHEMA pcm;
CREATE SCHEMA crm_customer_information;
DO $$ BEGIN
  FOR i IN 1..10 LOOP
    EXECUTE format('CREATE TABLE pcm.dict_%s (id int PRIMARY KEY, code text NOT NULL, name text)', i);
    EXECUTE format($q$INSERT INTO pcm.dict_%s SELECT g, 'A_' || g, 'Arşiv ' || g FROM generate_series(1, 50) g$q$, i);
  END LOOP;
  FOR i IN 1..40 LOOP
    EXECUTE format('CREATE TABLE crm_customer_information.entity_%s (id bigint PRIMARY KEY, label text, type_id int REFERENCES pcm.dict_1, archived_at timestamp)', i);
    EXECUTE format($q$INSERT INTO crm_customer_information.entity_%s SELECT g, 'arşiv ' || g, 1 + g %% 50, now() FROM generate_series(1, 5000) g$q$, i);
  END LOOP;
END $$;
CREATE TABLE pcm.product_offer (id bigint PRIMARY KEY, code text, name text, type_id int REFERENCES pcm.dict_1);
INSERT INTO pcm.product_offer SELECT g, 'PO' || g, 'Eski teklif ' || g, 1 + g % 50 FROM generate_series(1, 100000) g;
VACUUM ANALYZE;

\c postgres
SELECT datname, pg_size_pretty(pg_database_size(datname)) FROM pg_database WHERE datname LIKE 'mm_%' ORDER BY 1;
