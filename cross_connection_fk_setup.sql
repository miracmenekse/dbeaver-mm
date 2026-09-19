-- dbeaver-mm: cross-connection FK test data.
-- 1) Run the first block against a NEW SQLite database: /home/mirac/projects/mm_config_db
--    (the "configuration" connection).
CREATE TABLE service_spec (
    id   INTEGER PRIMARY KEY,
    code TEXT NOT NULL,
    name TEXT NOT NULL
);
INSERT INTO service_spec (id, code, name) VALUES
    (10, 'SRV_BILL', 'Faturalama'),
    (20, 'SRV_CRM',  'Musteri Yonetimi'),
    (30, 'SRV_ORD',  'Siparis Yonetimi');

-- 2) Run this against mm_test_db. No REFERENCES clause: the two tables live in different
--    databases, the link is only a DBeaver virtual foreign key (see UI_UX_MODERNIZASYON.md, K1).
ALTER TABLE bsn_flow_spec ADD COLUMN service_id INTEGER;
UPDATE bsn_flow_spec SET service_id = CASE id WHEN 100 THEN 10 WHEN 101 THEN 20 WHEN 102 THEN 30 ELSE 10 END;
