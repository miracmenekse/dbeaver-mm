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

-- 3) 12-column table (2026-09-19). First against mm_config_db:
CREATE TABLE channel_spec (
    id   INTEGER PRIMARY KEY,
    code TEXT NOT NULL,
    name TEXT NOT NULL
);
INSERT INTO channel_spec (id, code, name) VALUES
    (1, 'WEB',    'Web Portal'),
    (2, 'MOBILE', 'Mobil Uygulama'),
    (3, 'CC',     'Cagri Merkezi'),
    (4, 'STORE',  'Magaza');

-- 4) Then against mm_test_db. Every local table is referenced; flow_status and department twice.
--    service_id / channel_id point into mm_config_db: virtual FKs only.
CREATE TABLE bsn_order_spec (
    id             INTEGER PRIMARY KEY,
    label          TEXT,
    flow_id        INTEGER REFERENCES bsn_flow_spec (id),
    inter_id       INTEGER REFERENCES bsn_inter_spec (id),
    order_type_id  INTEGER REFERENCES order_type (id),
    status_id      INTEGER REFERENCES flow_status (id),
    prev_status_id INTEGER REFERENCES flow_status (id),
    priority_id    INTEGER REFERENCES priority_level (id),
    owner_dept_id  INTEGER REFERENCES department (id),
    target_dept_id INTEGER REFERENCES department (id),
    service_id     INTEGER,
    channel_id     INTEGER
);
INSERT INTO bsn_order_spec VALUES
    (1000, 'siparis 1', 100, 10, 2, 2, 1, 3, 2, 1, 10, 1),
    (1001, 'siparis 2', 101, 11, 1, 1, 1, 1, 1, 3, 20, 2),
    (1002, 'siparis 3', 102, 12, 2, 3, 2, 2, 3, 2, 30, 3),
    (1003, 'siparis 4', 103, 13, 3, 2, 1, 2, 1, 1, 10, 4),
    (1004, 'siparis 5', 100, 11, 1, 3, 2, 3, 2, 3, 20, 1),
    (1005, 'siparis 6', 101, 10, 2, 1, 1, 1, 3, 2, 30, 2),
    (1006, 'siparis 7', 102, 13, 3, 2, 1, 2, 2, 2, 10, 3),
    (1007, 'siparis 8', 103, 12, 1, 3, 2, 3, 1, 3, 20, 4);
