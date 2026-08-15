-- =====================================================================
-- PoC test kurulumu (SQLite): bsn_flow_spec + 3 farkli sozluk tablosu
-- Her referans tablonun birden fazla aciklama-adayi kolonu var; boylece
-- baslik butonuyla farkli kolonlari secip deneyebilirsin.
-- =====================================================================

-- SQLite'ta FK zorunlulugu baglanti basina acilir. DBeaver FK metadata'sini
-- tablo tanimindan okur, o yuzden asagidaki FOREIGN KEY tanimlari yeterli.
PRAGMA foreign_keys = ON;

-- Temiz baslangic (once cocuk tabloyu dusur)
DROP TABLE IF EXISTS bsn_flow_spec;
DROP TABLE IF EXISTS flow_status;
DROP TABLE IF EXISTS priority_level;
DROP TABLE IF EXISTS department;

-- ---------------------------------------------------------------------
-- 1) flow_status
-- ---------------------------------------------------------------------
CREATE TABLE flow_status (
    id          INTEGER PRIMARY KEY,
    code        TEXT NOT NULL,   -- or. NEW, ACTIVE
    name        TEXT NOT NULL,   -- or. Yeni, Aktif
    description TEXT             -- uzun aciklama
);

INSERT INTO flow_status (id, code, name, description) VALUES
    (1, 'NEW',    'Yeni',       'Yeni olusturulmus akis'),
    (2, 'ACTIVE', 'Aktif',      'Su anda calisan akis'),
    (3, 'DONE',   'Tamamlandi', 'Basariyla bitmis akis');

-- ---------------------------------------------------------------------
-- 2) priority_level
-- ---------------------------------------------------------------------
CREATE TABLE priority_level (
    id         INTEGER PRIMARY KEY,
    level_name TEXT NOT NULL,   -- or. Dusuk, Yuksek
    severity   TEXT NOT NULL    -- or. P3, P1
);

INSERT INTO priority_level (id, level_name, severity) VALUES
    (1, 'Dusuk',  'P3'),
    (2, 'Orta',   'P2'),
    (3, 'Yuksek', 'P1');

-- ---------------------------------------------------------------------
-- 3) department
-- ---------------------------------------------------------------------
CREATE TABLE department (
    id        INTEGER PRIMARY KEY,
    dept_code TEXT NOT NULL,   -- or. FIN, IT
    dept_name TEXT NOT NULL    -- or. Finans, Bilgi Islem
);

INSERT INTO department (id, dept_code, dept_name) VALUES
    (1, 'FIN', 'Finans'),
    (2, 'IT',  'Bilgi Islem'),
    (3, 'OPS', 'Operasyon');

-- ---------------------------------------------------------------------
-- 4) bsn_flow_spec  -> 3 farkli FK
-- ---------------------------------------------------------------------
CREATE TABLE bsn_flow_spec (
    id          INTEGER PRIMARY KEY,
    label       TEXT,
    status_id   INTEGER REFERENCES flow_status (id),
    priority_id INTEGER REFERENCES priority_level (id),
    dept_id     INTEGER REFERENCES department (id)
);

INSERT INTO bsn_flow_spec (id, label, status_id, priority_id, dept_id) VALUES
    (100, 'akis A', 2, 3, 2),
    (101, 'akis B', 1, 1, 1),
    (102, 'akis C', 3, 2, 3),
    (103, 'akis D', 2, 2, 1);
