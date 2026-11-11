-- ============================================================================
-- Bulk dummy data for the Billery PostgreSQL dev deployment.
--
-- Loaded by the application (DatabaseInitializer.loadDemoData) when LOAD_DEMO_DATA=true,
-- after Flyway has created the schema, and only into a database without customers.
-- For local development put LOAD_DEMO_DATA=true into backend/.env.
--
-- Volume (medium):
--   1 owner, 100 customers (every name once), 15 equipment, 30 articles, 30 services,
--   16 document_number_series rows (4 types x 4 years),
--   1420 invoices (700 INVOICE, 470 OFFER, 180 COST_CALCULATION,
--                  70 PARTED_INVOICE) spread over 2023-2026,
--   ~325 invoice_period rows (4-5 per PARTED_INVOICE),
--   ~3160 root invoice_item rows, ~570 sub-item rows (CUSTOM notes),
--   ~3500 document_history rows, ~400 payment rows.
--
-- The data is shown in demos: real umlauts, no repeated names, no "Demo"/"Dummy" texts in
-- visible fields, and a document history that matches each document's status and uses the
-- entries the application itself writes (CREATED, ISSUED, STATUS_CHANGED, PAYMENT_RECORDED,
-- CANCELLED). SchemaMigratorTest checks the uniqueness and the history on PostgreSQL.
--
-- Rows with explicit ids use ON CONFLICT DO NOTHING (or a NOT EXISTS gate), so
-- the file is safe to re-run. Every statement is ordered so that the foreign keys
-- hold when it finishes (PostgreSQL checks them at the end of each statement).
-- The identity sequences are moved past the explicit ids at the very end.
--
-- ============================================================================


-- ============================================================================
-- 1. OWNER  (1 row -- the demo Musterfirma)
-- ============================================================================

INSERT INTO owner (
    first_name, last_name, company_name, company_type,
    owner_postal_code, owner_city, owner_street, owner_house_number,
    phone, email, url, tax_id, iban, is_small_business, note
)
SELECT * FROM (VALUES
    ('Max', 'Mustermann', 'Musterfirma GmbH', 'GmbH',
     '12345', 'Musterstadt', 'Musterstraße', 1,
     '+49 123 456789', 'info@musterfirma.de', 'https://www.musterfirma.de',
     'DE123456789', 'DE89370400440532013000', FALSE,
     NULL)
) AS v(first_name, last_name, company_name, company_type,
       owner_postal_code, owner_city, owner_street, owner_house_number,
       phone, email, url, tax_id, iban, is_small_business, note)
WHERE NOT EXISTS (SELECT 1 FROM owner LIMIT 1);

-- ============================================================================
-- 2. CUSTOMERS  (100 rows: 30 hand-crafted + 70 generated, every first + last name once)
-- ============================================================================

INSERT INTO customer (id, first_name, last_name, postal_code, city, street, house_number, phone, email, tax_id, note)
VALUES
  ( 1, 'Anna',    'Müller',     '10115', 'Berlin',     'Hauptstraße',     12, '+49 30 1234567', 'anna.mueller@example.com',     'DE111111111', 'VIP-Kunde seit 2022'),
  ( 2, 'Peter',   'Schmidt',    '20095', 'Hamburg',    'Musterweg',        5, '+49 40 2345678', 'peter.schmidt@example.com',    NULL,          NULL),
  ( 3, 'Klaus',   'Weber',      '80331', 'München',   'Bahnhofstraße',   22, '+49 89 3456789', 'klaus.weber@example.com',      'DE222222222', 'Langjähriger Kunde'),
  ( 4, 'Sabine',  'Fischer',    '50667', 'Köln',      'Schildergasse',    3, '+49 221 4567890','sabine.fischer@example.com',   NULL,          'Steuerberatung Dr. Fischer & Partner'),
  ( 5, 'Markus',  'Bauer',      '60311', 'Frankfurt',  'Kaiserstraße',    45, '+49 69 5678901', 'markus.bauer@example.com',     'DE333333333', NULL),
  ( 6, 'Petra',   'Wagner',     '70173', 'Stuttgart',  'Königstraße',    17, '+49 711 6789012','petra.wagner@example.com',     NULL,          'Architekturbüro Wagner'),
  ( 7, 'Stefan',  'Becker',     '40213', 'Düsseldorf','Schadowstraße',   28, '+49 211 7890123','stefan.becker@example.com',    'DE444444444', NULL),
  ( 8, 'Julia',   'Hoffmann',   '04109', 'Leipzig',    'Grimmaische Str.', 9, '+49 341 8901234','julia.hoffmann@example.com',   NULL,          NULL),
  ( 9, 'Tobias',  'Schäfer',   '44135', 'Dortmund',   'Westenhellweg',    14, '+49 231 9012345','tobias.schaefer@example.com',  NULL,          'Bäckerei Schäfer'),
  (10, 'Nadine',  'Klein',      '28195', 'Bremen',     'Sögestraße',     8, '+49 421 1234500','nadine.klein@example.com',     NULL,          NULL),
  (11, 'Christian','Wolf',      '30159', 'Hannover',   'Bahnhofstraße',   30, '+49 511 2345601','christian.wolf@example.com',  'DE555555555', NULL),
  (12, 'Martina', 'Neumann',    '90402', 'Nürnberg',  'Königstraße',    66, '+49 911 3456702','martina.neumann@example.com',  NULL,          'Steuerkanzlei Neumann'),
  (13, 'Florian', 'Schwarz',    '01067', 'Dresden',    'Prager Straße',   12, '+49 351 4567803','florian.schwarz@example.com',  NULL,          NULL),
  (14, 'Anja',    'Zimmer',     '14471', 'Potsdam',    'Brandenburger Str.', 4,'+49 331 5678904','anja.zimmer@example.com',     NULL,          NULL),
  (15, 'Daniel',  'Braun',      '79098', 'Freiburg',   'Kaiser-Joseph-Str.', 200,'+49 761 6789015','daniel.braun@example.com',  NULL,          NULL),
  (16, 'Lisa',    'Hofmann',    '55116', 'Mainz',      'Am Brand',         5, '+49 6131 7890126','lisa.hofmann@example.com',   NULL,          'Mainzer Weingut Hofmann'),
  (17, 'Sebastian','Krämer',   '24103', 'Kiel',       'Holstenplatz',     1, '+49 431 8901237','sebastian.kraemer@example.com', NULL,        NULL),
  (18, 'Stefanie','Walter',     '99084', 'Erfurt',     'Anger',           20, '+49 361 9012348','stefanie.walter@example.com',  NULL,          NULL),
  (19, 'Matthias','Mayer',      '66111', 'Saarbrücken','St. Johanner Markt', 1,'+49 681 1234509','matthias.mayer@example.com', NULL,          NULL),
  (20, 'Christine','Böhm',     '07743', 'Jena',       'Markt',           11, '+49 3641 234510','christine.boehm@example.com',  NULL,          NULL),
  (21, 'Alexander','Werner',    '18435', 'Stralsund',  'Ostsee-Str.',     44, '+49 3831 345611','alexander.werner@example.com', 'DE666666666', NULL),
  (22, 'Birgit',  'Otto',       '54290', 'Trier',      'Hauptmarkt',       1, '+49 651 456712','birgit.otto@example.com',      NULL,          'Trierer Weinhandel'),
  (23, 'Jan',     'Stein',      '78462', 'Konstanz',   'Bodenseering',     3, '+49 7531 567813','jan.stein@example.com',        NULL,          NULL),
  (24, 'Verena',  'Jung',       '07747', 'Jena-Nord',  'Nordstraße',     27, '+49 3641 678914','verena.jung@example.com',      NULL,          NULL),
  (25, 'Holger',  'Vogel',      '98527', 'Suhl',       'Friedrich-König-Str.', 5,'+49 3681 789015','holger.vogel@example.com',  NULL,          NULL),
  (26, 'Silke',   'Sander',     '72764', 'Reutlingen', 'Kaiserstraße',   18, '+49 7121 890126','silke.sander@example.com',     NULL,          'Reutlinger Modehaus'),
  (27, 'Roland',  'Reinhardt',  '96450', 'Coburg',     'Mohrenstraße',   9, '+49 9561 901237','roland.reinhardt@example.com', NULL,          NULL),
  (28, 'Andrea',  'Pfeiffer',   '63739', 'Aschaffenburg','Roßmarkt',      2, '+49 6021 123458','andrea.pfeiffer@example.com',  NULL,          NULL),
  (29, 'Thorsten','Engel',      '26548', 'Norderney',  'Adenauerstraße', 10, '+49 4932 234569','thorsten.engel@example.com',   NULL,          'Inselhotel Engel'),
  (30, 'Manuela', 'Roth',       '76530', 'Baden-Baden','Sophienstraße',   7, '+49 7221 345670','manuela.roth@example.com',     'DE777777777', 'Weingut Roth')

ON CONFLICT (id) DO NOTHING;

-- Generated customers 31..100. 30 first names x 29 last names, picked with n % 30 and n % 29:
-- the lengths are coprime, so no pair repeats before n = 870. None of the 29 last names is used
-- by the hand-crafted customers above. The e-mail is derived from the name (umlauts spelled out).
INSERT INTO customer (id, first_name, last_name, postal_code, city, street, house_number, phone, email, tax_id, note)
WITH RECURSIVE seq(n) AS (
  SELECT 31 UNION ALL SELECT n+1 FROM seq WHERE n < 100
),
named AS (
  SELECT
    n,
    (ARRAY['Lukas', 'Marie', 'Jonas', 'Sophie', 'Felix', 'Laura', 'Paul', 'Emma', 'Leon', 'Mia',
           'Ben',   'Hannah','Elias', 'Lina',  'Maximilian', 'Anna',  'Tim', 'Lea',  'Niklas', 'Lara',
           'Jan',   'Julia', 'Tom',   'Sarah', 'Erik',  'Lena',  'David','Nora', 'Finn',  'Marlene'])[1 + (n % 30)] AS first_name,
    (ARRAY['Richter', 'Koch',    'Schröder', 'Krüger',  'Hartmann', 'Lange',   'Schmitt', 'Krause',
           'Meier',   'Lehmann', 'Köhler',   'Herrmann','König',    'Walther', 'Peters',  'Möller',
           'Kaiser',  'Fuchs',   'Scholz',   'Lang',    'Weiß',     'Jäger',   'Brandt',  'Haas',
           'Schulte', 'Graf',    'Dietrich', 'Kuhn',    'Busch'])[1 + (n % 29)] AS last_name
  FROM seq
)
SELECT
  n,
  first_name,
  last_name,
  LPAD(CAST(10000 + ((n * 137) % 89999) AS TEXT), 5, '0'),
  (ARRAY['Berlin', 'Hamburg', 'München', 'Köln', 'Frankfurt am Main', 'Stuttgart',
         'Düsseldorf', 'Leipzig', 'Dortmund', 'Bremen', 'Hannover', 'Nürnberg'])[1 + (n % 12)],
  (ARRAY['Hauptstraße','Bahnhofstraße','Schulstraße','Gartenweg','Lindenallee',
         'Dorfstraße','Mühlenweg','Kirchgasse','Bergstraße','Waldweg',
         'Blumenstraße','Parkstraße','Ringstraße','Feldweg','Brunnenstraße'])[1 + (n % 15)],
  1 + ((n * 7) % 200),
  CONCAT('+49 30 ', LPAD(CAST(1000000 + ((n * 31337) % 8999999) AS TEXT), 7, '0')),
  CONCAT(
    REPLACE(REPLACE(REPLACE(REPLACE(LOWER(CONCAT(first_name, '.', last_name)),
      'ä', 'ae'), 'ö', 'oe'), 'ü', 'ue'), 'ß', 'ss'),
    '@example.com'),
  CASE WHEN n % 3 = 0 THEN CONCAT('DE', LPAD(CAST(100000000 + ((n * 4242) % 899999999) AS TEXT), 9, '0')) ELSE NULL END,
  NULL
FROM named
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE id = n);

-- ============================================================================
-- 3. EQUIPMENT  (15 rows)
-- ============================================================================

INSERT INTO equipment (id, name, purchase_value, acquisition_date, expected_lifetime_months,
                       monthly_operating_cost, residual_value, status, notes)
VALUES
  ( 1, 'Bohrmaschine Profi XYZ',           1500.00, '2022-06-01',  60,  15.00,  200.00, 'active', 'Elektrobohrmaschine, gewerblich'),
  ( 2, 'Geschäfts-Laptop ThinkPad T14',   1400.00, '2023-01-15',  36,   0.00,  200.00, 'active', 'Lenovo ThinkPad T14 Gen3'),
  ( 3, 'Messgerät Laser GLM 50',          3200.00, '2021-03-10',  84,  25.00,  500.00, 'active', 'Bosch Professional Laser-Entfernungsmesser'),
  ( 4, 'Lieferwagen Mercedes Sprinter',   38000.00, '2020-09-01', 120, 180.00, 6000.00, 'active', '3.5t, Baujahr 2020, ca. 80tkm'),
  ( 5, '3D-Drucker Prusa MK4',             1100.00, '2023-11-20',  48,   5.00,  150.00, 'active', 'Filament-Drucker für Prototypen'),
  ( 6, 'CNC-Fräse 3020',                  5800.00, '2022-04-05',  96,  35.00,  800.00, 'active', '3-Achs CNC-Fräse 300x200mm'),
  ( 7, 'Schweißgerät MIG/MAG',           2200.00, '2021-07-12',  72,  20.00,  300.00, 'active', 'EWM Tetrix 230'),
  ( 8, 'Werkstattwagen 7 Schubladen',      1800.00, '2022-02-18', 120,   0.00,  200.00, 'active', 'Gedore, komplett mit Werkzeugsortiment'),
  ( 9, 'Hochdruckreiniger K7',              650.00, '2023-05-22',  60,   8.00,   80.00, 'active', 'Kärcher Premium für Außenreinigung'),
  (10, 'Akku-Bohrschrauber PSC 18 LI',      420.00, '2023-08-30',  36,   2.00,   50.00, 'active', 'Metabo 18V Akku-System'),
  (11, 'Laptop MacBook Pro 14',            2500.00, '2024-02-14',  48,   0.00,  400.00, 'active', 'M3 Pro, 18GB RAM'),
  (12, 'Server Dell PowerEdge R750',      9500.00, '2022-10-01',  84,  40.00, 1500.00, 'active', '2x Xeon Gold, 128GB ECC, 4TB SSD'),
  (13, 'Multimeter Fluke 87V',             780.00, '2021-11-20', 120,   0.00,  100.00, 'active', 'Industrie-Multimeter'),
  (14, 'Oszilloskop Rigol DS1054Z',        520.00, '2022-03-15',  84,   0.00,   60.00, 'active', '4-Kanal Digitaloszilloskop'),
  (15, 'Hebebühne 2-Säulen 4t',          6800.00, '2020-06-12', 144,  25.00,  900.00, 'active', 'TW S2-40, elektrisch')

ON CONFLICT (id) DO NOTHING;


-- ============================================================================
-- 4. ARTICLES  (30 rows)
-- ============================================================================

INSERT INTO article (id, article_name, purchase_value, sales_value, unit, article_description)
VALUES
  ( 1, 'Bohrmaschine Modell XYZ',         150.00,  250.00, 'Stück',  'Elektrobohrmaschine 18V'),
  ( 2, 'Geschäftsnotebook 14',            800.00, 1200.00, 'Stück',  'Business-Laptop, 16GB RAM, 512GB SSD'),
  ( 3, 'Schraubensatz 500-teilig',          25.00,   45.00, 'Set',     'Universal-Schraubensatz'),
  ( 4, 'Schutzbrille EN166',                 5.00,   12.00, 'Stück',  'Sicherheitsbrille kratzfest'),
  ( 5, 'Industriehandschuhe Gr. 10',         3.00,    7.50, 'Paar',    'Schnittschutz Level 5'),
  ( 6, 'Gehörschutz SNR 30',                8.00,   19.00, 'Stück',  'Kapselgehörschutz für Werkstatt'),
  ( 7, 'Schutzhelm DIN EN 397',             12.00,   29.00, 'Stück',  'Industriehelm mit Kinnriemen'),
  ( 8, 'Sicherheitsschuhe S3 Gr. 42',       45.00,   89.00, 'Paar',    'Durchtrittshemmend, wasserdicht'),
  ( 9, 'Werkzeugkoffer 145-teilig',          65.00,  135.00, 'Stück',  'Gedore Industriewerkzeuge'),
  (10, 'Multimeter digital',                35.00,   79.00, 'Stück',  'CAT III 600V'),
  (11, 'Lötkolben 60W',                     22.00,   49.00, 'Stück',  'Ersa Multitip'),
  (12, 'Lötzinn bleifrei 100g',              8.00,   18.00, 'Rolle',   'Sn99Cu1, 0.5mm'),
  (13, 'Kabelkanäle PVC 40x25mm 2m',         4.00,    9.50, 'Stück',  'Trapezform, hellgrau'),
  (14, 'NYM-J 3x1.5 Kabel 10m',             19.00,   38.00, 'Ring',    'Mantelleitung PVC'),
  (15, 'Unterputzdose Standard',             0.40,    1.20, 'Stück',  '60mm Tiefe'),
  (16, 'Schalterprogramm weiß',              6.00,   14.00, 'Stück',  'Unterputz-Schalter'),
  (17, 'Steckdose weiß',                     5.00,   12.00, 'Stück',  'Schuko, 16A'),
  (18, 'Lampe LED E27 9W',                   4.00,    9.90, 'Stück',  'Warmweiß 2700K, 800lm'),
  (19, 'Steckerleiste 6-fach',               9.00,   22.00, 'Stück',  'Mit Überspannungsschutz'),
  (20, 'USB-C-Kabel 2m',                     4.00,   11.50, 'Stück',  '100W PD, geflochten'),
  (21, 'Druckerpatrone HP 305 schwarz',     12.00,   26.00, 'Stück',  'Original HP'),
  (22, 'Druckerpapier A4 500 Blatt',         4.50,    9.90, 'Pack',    '80g/m2 holzfrei'),
  (23, 'Whiteboardmarker 4er Set',           3.50,    8.50, 'Set',     'Schwarz, rot, blau, grün'),
  (24, 'Kugelschreiber blau 10er Pack',      2.50,    6.50, 'Pack',    'Dokumentenecht'),
  (25, 'Büroklammern 1000 Stück',          1.20,    3.50, 'Pack',    '28mm, vernickelt'),
  (26, 'Ordner A4 8cm breit',                2.50,    6.90, 'Stück',  'Rückenbreite 80mm'),
  (27, 'Hektoklammer 26/6 5000er',           1.20,    3.20, 'Pack',    'Verzinkt'),
  (28, 'Locher 2-Loch 25 Blatt',             6.00,   14.50, 'Stück',  'Metallausführung'),
  (29, 'Heftgerät 25 Blatt',                7.50,   17.50, 'Stück',  'Inkl. 1000 Klammern'),
  (30, 'Tesafilm 19mm 33m',                  0.80,    2.50, 'Rolle',   'Transparent')

ON CONFLICT (id) DO NOTHING;


-- ============================================================================
-- 5. SERVICES  (30 rows)
-- Each service optionally references an article (id <= 30) and/or
-- equipment (id <= 15); the equipment links feed the amortisation view.
-- ============================================================================

INSERT INTO service (id, servicename, sales_value, article_id, equipment_id, unit, service_description)
VALUES
  ( 1, 'Maschinenvermietung Bohrmaschine',    50.00,  1,  1, 'Tag',     'Vermietung der Bohrmaschine pro Tag'),
  ( 2, 'IT-Support vor Ort',                  80.00, NULL, 2, 'Stunde',  'Stundenlohn IT-Support'),
  ( 3, 'Fachberatung',                       120.00, NULL, NULL, 'Stunde', 'Fachberatung vor Ort'),
  ( 4, 'Lieferung Standard',                  35.00,  3, NULL, 'Stück',  'Transport und Standardlieferung'),
  ( 5, 'Wartung Bohrmaschine',                90.00,  1,  1, 'Stück',  'Jährliche Wartung inkl. Verschleißteile'),
  ( 6, 'Schulung IT-Sicherheit',             450.00, NULL, 11, 'Tag',    'Tagesschulung für Mitarbeiter'),
  ( 7, 'Installation Netzwerk',              120.00, 19, 12, 'Stunde',  'Netzwerk-Verkabelung und Konfiguration'),
  ( 8, 'Datenrettung SSD',                   150.00, NULL, 11, 'Stück',  'Wiederherstellung beschädigter Datenträger'),
  ( 9, 'Konfiguration Server',               135.00, NULL, 12, 'Stunde',  'Server-Setup und Härtung'),
  (10, 'Schulung Mitarbeiter Office',        380.00, NULL, 11, 'Tag',    'Office-365 Tagesschulung'),
  (11, 'Lieferung Express',                   75.00,  3, NULL, 'Stück',  '24h-Express-Lieferung'),
  (12, 'Vor-Ort-Reparatur',                  110.00, NULL, NULL, 'Stunde', 'Reparatur vor Ort, Anfahrtspauschale separat'),
  (13, 'Wartung Lasermessgerät',            180.00, NULL,  3, 'Stück',  'Kalibrierung und Wartung'),
  (14, 'Schweißarbeiten MAG',                95.00, NULL,  7, 'Stunde',  'MIG/MAG-Schweißarbeiten'),
  (15, 'CNC-Fräsarbeiten',                  150.00, NULL,  6, 'Stunde',  'Stundenpreis CNC-Fräse inkl. Bediener'),
  (16, 'Wartung CNC-Fräse',                 420.00, NULL,  6, 'Stück',  'Quartalswartung inkl. Schmiersystem-Check'),
  (17, 'Drucker-Wartung',                     85.00, 21, NULL, 'Stück',  'Reinigung und Düsenreinigung'),
  (18, 'Lieferung International',            120.00,  3, NULL, 'Stück',  'Versand innerhalb EU'),
  (19, 'Büromaterial-Auffüllung',            45.00, 22, NULL, 'Stunde',  'Sortieren und Auffüllen Verbrauchsmaterial'),
  (20, 'Schulung Buchhaltung',               480.00, NULL, NULL, 'Tag',    'DATEV und Lexware Tagesschulung'),
  (21, 'Notfall-Support 24/7',               220.00, NULL, NULL, 'Stunde', 'Rund-um-die-Uhr Notfall-Hotline'),
  (22, 'Webentwicklung Backend',             110.00, NULL, 11, 'Stunde',  'Java/Spring Backend-Entwicklung'),
  (23, 'Webentwicklung Frontend',             95.00, NULL, 11, 'Stunde',  'Vue.js Frontend-Entwicklung'),
  (24, 'Code-Review',                        130.00, NULL, NULL, 'Stunde', 'Externer Code-Review inkl. Bericht'),
  (25, 'Beratung Datenschutz DSGVO',         180.00, NULL, NULL, 'Stunde', 'DSGVO-Beratung'),
  (26, 'Wartung Klimaanlage',                160.00, NULL,  9, 'Stück',  'Filterwechsel und Kältemittel-Check'),
  (27, 'Hochdruckreinigung',                  85.00, NULL,  9, 'Stunde',  'Hochdruckreinigung Außenbereich'),
  (28, 'Akku-Wartung',                        65.00, 10, 10, 'Stück',  'Akku-Health-Check und Tausch'),
  (29, 'Schulung Schweißtechnik',           450.00, NULL,  7, 'Tag',    'MIG/MAG/WIG Praxisschulung'),
  (30, 'Beratung Energieeffizienz',          140.00, NULL, NULL, 'Stunde', 'Energieberatung nach GEG')

ON CONFLICT (id) DO NOTHING;


-- ============================================================================
-- 6. DOCUMENT NUMBER SERIES  (16 rows = 4 types x 4 years)
-- Counts match the planned distribution in section 7 (post-generation).
-- ============================================================================

INSERT INTO document_number_series (document_type_id, doc_year, last_number)
VALUES
  (1, 2023, 100),  -- INVOICE
  (1, 2024, 150),
  (1, 2025, 200),
  (1, 2026, 250),
  (2, 2023,  80),  -- OFFER
  (2, 2024, 100),
  (2, 2025, 130),
  (2, 2026, 160),
  (3, 2023,  30),  -- COST_CALCULATION
  (3, 2024,  40),
  (3, 2025,  50),
  (3, 2026,  60),
  (4, 2023,  10),  -- PARTED_INVOICE
  (4, 2024,  15),
  (4, 2025,  20),
  (4, 2026,  25)

ON CONFLICT (tenant_id, document_type_id, doc_year) DO NOTHING;

-- ============================================================================
-- 7. INVOICES  (1420 rows: 4 year-generators)
--
-- Status distribution per type (matches the plan from the dev plan):
--   INVOICE:        50% PAID, 30% SENT, 10% DRAFT, 5% CANCELLED, 5% CONVERTED
--   OFFER:          35% ACCEPTED, 25% SENT, 20% DRAFT, 10% REJECTED, 10% CONVERTED
--   COST_CALC:      40% ACCEPTED, 25% SENT, 20% DRAFT, 10% REJECTED, 5% CONVERTED
--   PARTED_INVOICE: 70% PAID, 30% SENT
--
-- Note: rows that used to be tagged with the stored OVERDUE status (id=7)
-- are now stored as SENT (id=2). The UI still surfaces them as overdue
-- because due_date is in the past and paid_date is NULL; the "overdue" state
-- is now a derived property of (SENT, past due_date, no paid_date) rather than
-- a stored flag. See utils/documentStatus.js#isInvoiceOverdue.
--
-- document_number pattern: PREFIX-YYYY-NNN (RE, AN, KV, TR).
--
-- ID layout (1..1420, contiguous):
--   2023: ids 1..220      (100 INVOICE, 80 OFFER, 30 COST_CALC, 10 PARTED)
--   2024: ids 221..525    (150 INVOICE, 100 OFFER, 40 COST_CALC, 15 PARTED)
--   2025: ids 526..925    (200 INVOICE, 130 OFFER, 50 COST_CALC, 20 PARTED)
--   2026: ids 926..1420   (250 INVOICE, 160 OFFER, 60 COST_CALC, 25 PARTED)
-- ============================================================================

-- 7a. Year 2023 -- 220 docs
INSERT INTO invoice (
    id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes,
    document_number, version, is_latest, version_group_id,
    document_type_id, document_status_id,
    work_start_date, work_end_date,
    parent_invoice_id, period_id, converted_to_invoice_id,
    offer_valid_until, cost_calculation_basis, terms_and_conditions
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 220
)
SELECT
  n,
  1 + ((n * 7) % 100),
  (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0)) AS INT)),
  (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0 + 30)) AS INT)),
  ROUND(80 + ((n * 47) % 4800) + ((n * 11) % 70) * 0.01, 2),
  CASE WHEN n % 17 = 0 THEN 0.07 WHEN n % 23 = 0 THEN 0.00 ELSE 0.19 END,
  CASE
    WHEN (CASE WHEN n <= 100 THEN 1 WHEN n <= 180 THEN 2 WHEN n <= 210 THEN 3 ELSE 4 END) IN (1, 4)
         AND (CASE WHEN n <= 100 THEN
                (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
              WHEN n <= 180 THEN
                (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
              WHEN n <= 210 THEN
                (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
              ELSE
                (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
              END) = 6
    THEN (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0 + 14)) AS INT))
    ELSE NULL
  END,
  NULL,
  CASE
    WHEN n <= 100 THEN CONCAT('RE-2023-', LPAD(CAST(n AS TEXT), 3, '0'))
    WHEN n <= 180 THEN CONCAT('AN-2023-', LPAD(CAST(n - 100 AS TEXT), 3, '0'))
    WHEN n <= 210 THEN CONCAT('KV-2023-', LPAD(CAST(n - 180 AS TEXT), 3, '0'))
    ELSE                  CONCAT('TR-2023-', LPAD(CAST(n - 210 AS TEXT), 3, '0'))
  END,
  1, 1, n,
  CASE WHEN n <= 100 THEN 1 WHEN n <= 180 THEN 2 WHEN n <= 210 THEN 3 ELSE 4 END,
  CASE WHEN n <= 100 THEN
    (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
  WHEN n <= 180 THEN
    (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
  WHEN n <= 210 THEN
    (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
  ELSE
    (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
  END,
  CASE WHEN n <= 100 THEN (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0 - 10)) AS INT)) ELSE NULL END,
  CASE WHEN n <= 100 THEN (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0 + 5)) AS INT)) ELSE NULL END,
  NULL, NULL, NULL,
  CASE WHEN (n > 100 AND n <= 180) THEN (CAST('2023-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 220.0 + 28)) AS INT)) ELSE NULL END,
  CASE WHEN n > 180 AND n <= 210 THEN 'Kalkulation basiert auf Erfahrungswerten, Materialpreise können schwanken' ELSE NULL END,
  'Zahlung innerhalb 30 Tagen netto'
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM invoice WHERE id = n);

-- 7b. Year 2024 -- 305 docs (ids 221..525)
INSERT INTO invoice (
    id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes,
    document_number, version, is_latest, version_group_id,
    document_type_id, document_status_id,
    work_start_date, work_end_date,
    parent_invoice_id, period_id, converted_to_invoice_id,
    offer_valid_until, cost_calculation_basis, terms_and_conditions
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 305
)
SELECT
  220 + n,
  1 + ((n * 11) % 100),
  (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0)) AS INT)),
  (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0 + 30)) AS INT)),
  ROUND(80 + ((n * 53) % 4800) + ((n * 13) % 70) * 0.01, 2),
  CASE WHEN n % 17 = 0 THEN 0.07 WHEN n % 23 = 0 THEN 0.00 ELSE 0.19 END,
  CASE
    WHEN (CASE WHEN n <= 150 THEN 1 WHEN n <= 250 THEN 2 WHEN n <= 290 THEN 3 ELSE 4 END) IN (1, 4)
         AND (CASE WHEN n <= 150 THEN
                (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
              WHEN n <= 250 THEN
                (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
              WHEN n <= 290 THEN
                (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
              ELSE
                (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
              END) = 6
    THEN (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0 + 14)) AS INT))
    ELSE NULL
  END,
  NULL,
  CASE
    WHEN n <= 150 THEN CONCAT('RE-2024-', LPAD(CAST(n AS TEXT), 3, '0'))
    WHEN n <= 250 THEN CONCAT('AN-2024-', LPAD(CAST(n - 150 AS TEXT), 3, '0'))
    WHEN n <= 290 THEN CONCAT('KV-2024-', LPAD(CAST(n - 250 AS TEXT), 3, '0'))
    ELSE                  CONCAT('TR-2024-', LPAD(CAST(n - 290 AS TEXT), 3, '0'))
  END,
  1, 1, 220 + n,
  CASE WHEN n <= 150 THEN 1 WHEN n <= 250 THEN 2 WHEN n <= 290 THEN 3 ELSE 4 END,
  CASE WHEN n <= 150 THEN
    (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
  WHEN n <= 250 THEN
    (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
  WHEN n <= 290 THEN
    (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
  ELSE
    (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
  END,
  CASE WHEN n <= 150 THEN (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0 - 10)) AS INT)) ELSE NULL END,
  CASE WHEN n <= 150 THEN (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0 + 5)) AS INT)) ELSE NULL END,
  NULL, NULL, NULL,
  CASE WHEN (n > 150 AND n <= 250) THEN (CAST('2024-01-01' AS DATE) + CAST(ROUND(((n - 1) * 366 / 305.0 + 28)) AS INT)) ELSE NULL END,
  CASE WHEN n > 250 AND n <= 290 THEN 'Kalkulation basiert auf Erfahrungswerten, Materialpreise können schwanken' ELSE NULL END,
  'Zahlung innerhalb 30 Tagen netto'
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM invoice WHERE id = 220 + n);

-- 7c. Year 2025 -- 400 docs (ids 526..925)
INSERT INTO invoice (
    id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes,
    document_number, version, is_latest, version_group_id,
    document_type_id, document_status_id,
    work_start_date, work_end_date,
    parent_invoice_id, period_id, converted_to_invoice_id,
    offer_valid_until, cost_calculation_basis, terms_and_conditions
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 400
)
SELECT
  525 + n,
  1 + ((n * 13) % 100),
  (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0)) AS INT)),
  (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0 + 30)) AS INT)),
  ROUND(80 + ((n * 59) % 4800) + ((n * 17) % 70) * 0.01, 2),
  CASE WHEN n % 17 = 0 THEN 0.07 WHEN n % 23 = 0 THEN 0.00 ELSE 0.19 END,
  CASE
    WHEN (CASE WHEN n <= 200 THEN 1 WHEN n <= 330 THEN 2 WHEN n <= 380 THEN 3 ELSE 4 END) IN (1, 4)
         AND (CASE WHEN n <= 200 THEN
                (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
              WHEN n <= 330 THEN
                (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
              WHEN n <= 380 THEN
                (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
              ELSE
                (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
              END) = 6
    THEN (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0 + 14)) AS INT))
    ELSE NULL
  END,
  NULL,
  CASE
    WHEN n <= 200 THEN CONCAT('RE-2025-', LPAD(CAST(n AS TEXT), 3, '0'))
    WHEN n <= 330 THEN CONCAT('AN-2025-', LPAD(CAST(n - 200 AS TEXT), 3, '0'))
    WHEN n <= 380 THEN CONCAT('KV-2025-', LPAD(CAST(n - 330 AS TEXT), 3, '0'))
    ELSE                  CONCAT('TR-2025-', LPAD(CAST(n - 380 AS TEXT), 3, '0'))
  END,
  1, 1, 525 + n,
  CASE WHEN n <= 200 THEN 1 WHEN n <= 330 THEN 2 WHEN n <= 380 THEN 3 ELSE 4 END,
  CASE WHEN n <= 200 THEN
    (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
  WHEN n <= 330 THEN
    (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
  WHEN n <= 380 THEN
    (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
  ELSE
    (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
  END,
  CASE WHEN n <= 200 THEN (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0 - 10)) AS INT)) ELSE NULL END,
  CASE WHEN n <= 200 THEN (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0 + 5)) AS INT)) ELSE NULL END,
  NULL, NULL, NULL,
  CASE WHEN (n > 200 AND n <= 330) THEN (CAST('2025-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 400.0 + 28)) AS INT)) ELSE NULL END,
  CASE WHEN n > 330 AND n <= 380 THEN 'Kalkulation basiert auf Erfahrungswerten, Materialpreise können schwanken' ELSE NULL END,
  'Zahlung innerhalb 30 Tagen netto'
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM invoice WHERE id = 525 + n);

-- 7d. Year 2026 -- 495 docs (ids 926..1420)
INSERT INTO invoice (
    id, customer_id, invoice_date, due_date, total_price, tax_rate, paid_date, notes,
    document_number, version, is_latest, version_group_id,
    document_type_id, document_status_id,
    work_start_date, work_end_date,
    parent_invoice_id, period_id, converted_to_invoice_id,
    offer_valid_until, cost_calculation_basis, terms_and_conditions
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 495
)
SELECT
  925 + n,
  1 + ((n * 17) % 100),
  (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0)) AS INT)),
  (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0 + 30)) AS INT)),
  ROUND(80 + ((n * 61) % 4800) + ((n * 19) % 70) * 0.01, 2),
  CASE WHEN n % 17 = 0 THEN 0.07 WHEN n % 23 = 0 THEN 0.00 ELSE 0.19 END,
  CASE
    WHEN (CASE WHEN n <= 250 THEN 1 WHEN n <= 410 THEN 2 WHEN n <= 470 THEN 3 ELSE 4 END) IN (1, 4)
         AND (CASE WHEN n <= 250 THEN
                (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
              WHEN n <= 410 THEN
                (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
              WHEN n <= 470 THEN
                (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
              ELSE
                (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
              END) = 6
    THEN (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0 + 14)) AS INT))
    ELSE NULL
  END,
  NULL,
  CASE
    WHEN n <= 250 THEN CONCAT('RE-2026-', LPAD(CAST(n AS TEXT), 3, '0'))
    WHEN n <= 410 THEN CONCAT('AN-2026-', LPAD(CAST(n - 250 AS TEXT), 3, '0'))
    WHEN n <= 470 THEN CONCAT('KV-2026-', LPAD(CAST(n - 410 AS TEXT), 3, '0'))
    ELSE                  CONCAT('TR-2026-', LPAD(CAST(n - 470 AS TEXT), 3, '0'))
  END,
  1, 1, 925 + n,
  CASE WHEN n <= 250 THEN 1 WHEN n <= 410 THEN 2 WHEN n <= 470 THEN 3 ELSE 4 END,
  CASE WHEN n <= 250 THEN
    (ARRAY[6,6,6,6,6,6,6,6,6,6, 2,2,2,2,2,2,2, 1,1, 8, 5])[1 + (n % 20)]
  WHEN n <= 410 THEN
    (ARRAY[3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4,4, 5,5])[1 + (n % 20)]
  WHEN n <= 470 THEN
    (ARRAY[3,3,3,3,3,3,3,3, 2,2,2,2,2, 1,1,1,1, 4, 4, 5])[1 + (n % 20)]
  ELSE
    (ARRAY[6,6,6,6,6,6, 2,2,2,2])[1 + (n % 10)]
  END,
  CASE WHEN n <= 250 THEN (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0 - 10)) AS INT)) ELSE NULL END,
  CASE WHEN n <= 250 THEN (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0 + 5)) AS INT)) ELSE NULL END,
  NULL, NULL, NULL,
  CASE WHEN (n > 250 AND n <= 410) THEN (CAST('2026-01-01' AS DATE) + CAST(ROUND(((n - 1) * 365 / 495.0 + 28)) AS INT)) ELSE NULL END,
  CASE WHEN n > 410 AND n <= 470 THEN 'Kalkulation basiert auf Erfahrungswerten, Materialpreise können schwanken' ELSE NULL END,
  'Zahlung innerhalb 30 Tagen netto'
FROM seq
WHERE NOT EXISTS (SELECT 1 FROM invoice WHERE id = 925 + n);

-- ============================================================================
-- 8. INVOICE PERIODS  (~325 rows for the 70 PARTED_INVOICE documents)
--
-- The 70 PARTED_INVOICE docs are at:
--   2023: ids 211..220  (10 docs)
--   2024: ids 511..525  (15 docs)
--   2025: ids 906..925  (20 docs)
--   2026: ids 1396..1420 (25 docs)
--
-- Each gets 4-5 periods that sum to 100 percent.
-- ============================================================================

-- 2023: ids 211..220, 4 periods each, ids 1..40
INSERT INTO invoice_period (
    id, parent_invoice_id, period_number, period_start_date, period_end_date,
    description, percentage_of_total, previous_period_id
)
WITH RECURSIVE
  parted AS (
    SELECT 211 AS invoice_id, 1 AS seq_in_year UNION ALL
    SELECT invoice_id + 1, seq_in_year + 1 FROM parted WHERE seq_in_year < 10
  ),
  seq AS (
    SELECT 1 AS period_n UNION ALL SELECT period_n + 1 FROM seq WHERE period_n < 4
  )
SELECT
  ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n),
  p.invoice_id,
  s.period_n,
  (CAST((CAST('2023-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 36)) AS INT)) AS DATE) + CAST(ROUND(((s.period_n - 1) * 30)) AS INT)),
  (CAST((CAST('2023-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 36)) AS INT)) AS DATE) + CAST(ROUND((s.period_n * 30 - 1)) AS INT)),
  CONCAT('Abschnitt ', s.period_n, ' von TR-2023-', LPAD(CAST(p.seq_in_year AS TEXT), 3, '0')),
  CASE s.period_n WHEN 1 THEN 30 WHEN 2 THEN 30 WHEN 3 THEN 25 WHEN 4 THEN 15 END,
  CASE WHEN s.period_n = 1 THEN NULL
       ELSE ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n) - 1 END
FROM parted p CROSS JOIN seq s
WHERE NOT EXISTS (
  SELECT 1 FROM invoice_period
  WHERE parent_invoice_id = p.invoice_id AND period_number = s.period_n
);

-- 2024: ids 511..525, 4 periods each, ids 41..100
INSERT INTO invoice_period (
    id, parent_invoice_id, period_number, period_start_date, period_end_date,
    description, percentage_of_total, previous_period_id
)
WITH RECURSIVE
  parted AS (
    SELECT 511 AS invoice_id, 1 AS seq_in_year UNION ALL
    SELECT invoice_id + 1, seq_in_year + 1 FROM parted WHERE seq_in_year < 15
  ),
  seq AS (
    SELECT 1 AS period_n UNION ALL SELECT period_n + 1 FROM seq WHERE period_n < 4
  )
SELECT
  40 + ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n),
  p.invoice_id,
  s.period_n,
  (CAST((CAST('2024-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 24)) AS INT)) AS DATE) + CAST(ROUND(((s.period_n - 1) * 21)) AS INT)),
  (CAST((CAST('2024-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 24)) AS INT)) AS DATE) + CAST(ROUND((s.period_n * 21 - 1)) AS INT)),
  CONCAT('Abschnitt ', s.period_n, ' von TR-2024-', LPAD(CAST(p.seq_in_year AS TEXT), 3, '0')),
  CASE s.period_n WHEN 1 THEN 30 WHEN 2 THEN 30 WHEN 3 THEN 25 WHEN 4 THEN 15 END,
  CASE WHEN s.period_n = 1 THEN NULL
       ELSE 40 + (ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n) - 1) END
FROM parted p CROSS JOIN seq s
WHERE NOT EXISTS (
  SELECT 1 FROM invoice_period
  WHERE parent_invoice_id = p.invoice_id AND period_number = s.period_n
);

-- 2025: ids 906..925, 5 periods each, ids 101..200
INSERT INTO invoice_period (
    id, parent_invoice_id, period_number, period_start_date, period_end_date,
    description, percentage_of_total, previous_period_id
)
WITH RECURSIVE
  parted AS (
    SELECT 906 AS invoice_id, 1 AS seq_in_year UNION ALL
    SELECT invoice_id + 1, seq_in_year + 1 FROM parted WHERE seq_in_year < 20
  ),
  seq AS (
    SELECT 1 AS period_n UNION ALL SELECT period_n + 1 FROM seq WHERE period_n < 5
  )
SELECT
  100 + ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n),
  p.invoice_id,
  s.period_n,
  (CAST((CAST('2025-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 18)) AS INT)) AS DATE) + CAST(ROUND(((s.period_n - 1) * 18)) AS INT)),
  (CAST((CAST('2025-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 18)) AS INT)) AS DATE) + CAST(ROUND((s.period_n * 18 - 1)) AS INT)),
  CONCAT('Abschnitt ', s.period_n, ' von TR-2025-', LPAD(CAST(p.seq_in_year AS TEXT), 3, '0')),
  CASE s.period_n WHEN 1 THEN 25 WHEN 2 THEN 25 WHEN 3 THEN 20 WHEN 4 THEN 20 WHEN 5 THEN 10 END,
  CASE WHEN s.period_n = 1 THEN NULL
       ELSE 100 + (ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n) - 1) END
FROM parted p CROSS JOIN seq s
WHERE NOT EXISTS (
  SELECT 1 FROM invoice_period
  WHERE parent_invoice_id = p.invoice_id AND period_number = s.period_n
);

-- 2026: ids 1396..1420, 5 periods each, ids 201..325
INSERT INTO invoice_period (
    id, parent_invoice_id, period_number, period_start_date, period_end_date,
    description, percentage_of_total, previous_period_id
)
WITH RECURSIVE
  parted AS (
    SELECT 1396 AS invoice_id, 1 AS seq_in_year UNION ALL
    SELECT invoice_id + 1, seq_in_year + 1 FROM parted WHERE seq_in_year < 25
  ),
  seq AS (
    SELECT 1 AS period_n UNION ALL SELECT period_n + 1 FROM seq WHERE period_n < 5
  )
SELECT
  200 + ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n),
  p.invoice_id,
  s.period_n,
  (CAST((CAST('2026-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 14)) AS INT)) AS DATE) + CAST(ROUND(((s.period_n - 1) * 14)) AS INT)),
  (CAST((CAST('2026-01-01' AS DATE) + CAST(ROUND(((p.seq_in_year - 1) * 14)) AS INT)) AS DATE) + CAST(ROUND((s.period_n * 14 - 1)) AS INT)),
  CONCAT('Abschnitt ', s.period_n, ' von TR-2026-', LPAD(CAST(p.seq_in_year AS TEXT), 3, '0')),
  CASE s.period_n WHEN 1 THEN 25 WHEN 2 THEN 25 WHEN 3 THEN 20 WHEN 4 THEN 20 WHEN 5 THEN 10 END,
  CASE WHEN s.period_n = 1 THEN NULL
       ELSE 200 + (ROW_NUMBER() OVER (ORDER BY p.invoice_id, s.period_n) - 1) END
FROM parted p CROSS JOIN seq s
WHERE NOT EXISTS (
  SELECT 1 FROM invoice_period
  WHERE parent_invoice_id = p.invoice_id AND period_number = s.period_n
);

-- 8b. Backfill invoice.period_id to point at the first period of each parted invoice.
UPDATE invoice i
SET period_id = p.first_period_id
FROM (
  SELECT parent_invoice_id, MIN(id) AS first_period_id
  FROM invoice_period
  GROUP BY parent_invoice_id
) p
WHERE p.parent_invoice_id = i.id
  AND i.document_type_id = 4
  AND i.period_id IS NULL;

-- ============================================================================
-- 9. INVOICE ITEMS (ROOT)  -- ~3160 rows total
-- They reference the 30 articles / 30 services with multipliers coprime to 30, so every
-- catalog entry is used.
-- ARTICLE / SERVICE only (CUSTOM is reserved for sub-items, enforced by the
-- application layer in DocumentService). Items get explicit ids 1..3160
-- for deterministic sub-item references.
-- ============================================================================

-- 9a. Items for INVOICE docs (700 docs, 2 items each = 1400 items, ids 1..1400)
--     INVOICE ids: 1..100, 221..370, 526..725, 926..1175
INSERT INTO invoice_item (
    id, invoice_id, item_type, item_id, item_name, item_description,
    quantity, unit_price, total_price, parent_item_id
)
WITH RECURSIVE
  band_a AS (SELECT 1   AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_a WHERE invoice_id < 100),
  band_b AS (SELECT 221 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_b WHERE invoice_id < 370),
  band_c AS (SELECT 526 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_c WHERE invoice_id < 725),
  band_d AS (SELECT 926 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_d WHERE invoice_id < 1175),
  all_docs AS (
    SELECT invoice_id FROM band_a UNION ALL SELECT invoice_id FROM band_b
    UNION ALL SELECT invoice_id FROM band_c UNION ALL SELECT invoice_id FROM band_d
  ),
  seq AS (SELECT 1 AS line_no UNION ALL SELECT line_no + 1 FROM seq WHERE line_no < 2)
SELECT
  ROW_NUMBER() OVER (ORDER BY d.invoice_id, s.line_no),
  d.invoice_id,
  CASE WHEN s.line_no = 1 THEN 'SERVICE' ELSE 'ARTICLE' END,
  CASE WHEN s.line_no = 1 THEN 1 + ((d.invoice_id * 7) % 30) ELSE 1 + ((d.invoice_id * 11) % 30) END,
  NULL, NULL,
  CASE WHEN s.line_no = 1 THEN 1 + ((d.invoice_id % 10))  ELSE 1 + ((d.invoice_id * 3) % 20) END,
  CASE WHEN s.line_no = 1 THEN 80 + ((d.invoice_id * 11) % 120) ELSE 20 + ((d.invoice_id * 13) % 180) END,
  ROUND(
    (CASE WHEN s.line_no = 1 THEN 1 + ((d.invoice_id % 10))  ELSE 1 + ((d.invoice_id * 3) % 20) END)
    *
    (CASE WHEN s.line_no = 1 THEN 80 + ((d.invoice_id * 11) % 120) ELSE 20 + ((d.invoice_id * 13) % 180) END)
  , 2),
  NULL
FROM all_docs d CROSS JOIN seq s
WHERE NOT EXISTS (SELECT 1 FROM invoice_item WHERE invoice_id = d.invoice_id AND id <= 1400);

-- 9b. Items for OFFER docs (470 docs, 2 items each = 940 items, ids 1401..2340)
--     OFFER ids: 101..180, 371..470, 726..855, 1176..1335
INSERT INTO invoice_item (
    id, invoice_id, item_type, item_id, item_name, item_description,
    quantity, unit_price, total_price, parent_item_id
)
WITH RECURSIVE
  band_a AS (SELECT 101  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_a WHERE invoice_id < 180),
  band_b AS (SELECT 371  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_b WHERE invoice_id < 470),
  band_c AS (SELECT 726  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_c WHERE invoice_id < 855),
  band_d AS (SELECT 1176 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_d WHERE invoice_id < 1335),
  all_docs AS (
    SELECT invoice_id FROM band_a UNION ALL SELECT invoice_id FROM band_b
    UNION ALL SELECT invoice_id FROM band_c UNION ALL SELECT invoice_id FROM band_d
  ),
  seq AS (SELECT 1 AS line_no UNION ALL SELECT line_no + 1 FROM seq WHERE line_no < 2)
SELECT
  1400 + ROW_NUMBER() OVER (ORDER BY d.invoice_id, s.line_no),
  d.invoice_id,
  CASE WHEN s.line_no = 1 THEN 'SERVICE' ELSE 'ARTICLE' END,
  CASE WHEN s.line_no = 1 THEN 1 + ((d.invoice_id * 13) % 30) ELSE 1 + ((d.invoice_id * 17) % 30) END,
  NULL, NULL,
  CASE WHEN s.line_no = 1 THEN 2 + ((d.invoice_id * 2) % 8) ELSE 1 + ((d.invoice_id * 4) % 15) END,
  CASE WHEN s.line_no = 1 THEN 100 + ((d.invoice_id * 13) % 150) ELSE 30 + ((d.invoice_id * 17) % 200) END,
  ROUND(
    (CASE WHEN s.line_no = 1 THEN 2 + ((d.invoice_id * 2) % 8) ELSE 1 + ((d.invoice_id * 4) % 15) END)
    *
    (CASE WHEN s.line_no = 1 THEN 100 + ((d.invoice_id * 13) % 150) ELSE 30 + ((d.invoice_id * 17) % 200) END)
  , 2),
  NULL
FROM all_docs d CROSS JOIN seq s
WHERE NOT EXISTS (SELECT 1 FROM invoice_item WHERE invoice_id = d.invoice_id AND id > 1400 AND id <= 2400);

-- 9c. Items for COST_CALCULATION docs (180 docs, 3 items each = 540 items, ids 2341..2880)
--     COST_CALC ids: 181..210, 471..510, 856..905, 1336..1395
INSERT INTO invoice_item (
    id, invoice_id, item_type, item_id, item_name, item_description,
    quantity, unit_price, total_price, parent_item_id
)
WITH RECURSIVE
  band_a AS (SELECT 181  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_a WHERE invoice_id < 210),
  band_b AS (SELECT 471  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_b WHERE invoice_id < 510),
  band_c AS (SELECT 856  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_c WHERE invoice_id < 905),
  band_d AS (SELECT 1336 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_d WHERE invoice_id < 1395),
  all_docs AS (
    SELECT invoice_id FROM band_a UNION ALL SELECT invoice_id FROM band_b
    UNION ALL SELECT invoice_id FROM band_c UNION ALL SELECT invoice_id FROM band_d
  ),
  seq AS (SELECT 1 AS line_no UNION ALL SELECT line_no + 1 FROM seq WHERE line_no < 3)
SELECT
  2340 + ROW_NUMBER() OVER (ORDER BY d.invoice_id, s.line_no),
  d.invoice_id,
  CASE WHEN s.line_no = 1 THEN 'SERVICE' ELSE 'ARTICLE' END,
  CASE WHEN s.line_no = 1 THEN 1 + ((d.invoice_id * 11) % 30) ELSE 1 + ((d.invoice_id * 13) % 30) END,
  NULL, NULL,
  CASE WHEN s.line_no = 1 THEN 5 + ((d.invoice_id * 3) % 20) ELSE 2 + ((d.invoice_id * 5) % 30) END,
  CASE WHEN s.line_no = 1 THEN 90 + ((d.invoice_id * 17) % 100) ELSE 25 + ((d.invoice_id * 19) % 150) END,
  ROUND(
    (CASE WHEN s.line_no = 1 THEN 5 + ((d.invoice_id * 3) % 20) ELSE 2 + ((d.invoice_id * 5) % 30) END)
    *
    (CASE WHEN s.line_no = 1 THEN 90 + ((d.invoice_id * 17) % 100) ELSE 25 + ((d.invoice_id * 19) % 150) END)
  , 2),
  NULL
FROM all_docs d CROSS JOIN seq s
WHERE NOT EXISTS (SELECT 1 FROM invoice_item WHERE invoice_id = d.invoice_id AND id > 2340 AND id <= 2900);

-- 9d. Items for PARTED_INVOICE docs (70 docs, 4 items each = 280 items, ids 2881..3160)
--     PARTED ids: 211..220, 511..525, 906..925, 1396..1420
INSERT INTO invoice_item (
    id, invoice_id, item_type, item_id, item_name, item_description,
    quantity, unit_price, total_price, parent_item_id
)
WITH RECURSIVE
  band_a AS (SELECT 211  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_a WHERE invoice_id < 220),
  band_b AS (SELECT 511  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_b WHERE invoice_id < 525),
  band_c AS (SELECT 906  AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_c WHERE invoice_id < 925),
  band_d AS (SELECT 1396 AS invoice_id UNION ALL SELECT invoice_id + 1 FROM band_d WHERE invoice_id < 1420),
  all_docs AS (
    SELECT invoice_id FROM band_a UNION ALL SELECT invoice_id FROM band_b
    UNION ALL SELECT invoice_id FROM band_c UNION ALL SELECT invoice_id FROM band_d
  ),
  seq AS (SELECT 1 AS line_no UNION ALL SELECT line_no + 1 FROM seq WHERE line_no < 4)
SELECT
  2880 + ROW_NUMBER() OVER (ORDER BY d.invoice_id, s.line_no),
  d.invoice_id,
  CASE WHEN s.line_no <= 2 THEN 'SERVICE' ELSE 'ARTICLE' END,
  CASE WHEN s.line_no <= 2 THEN 1 + ((d.invoice_id * 7) % 30) ELSE 1 + ((d.invoice_id * 11) % 30) END,
  NULL, NULL,
  CASE WHEN s.line_no <= 2 THEN 3 + ((d.invoice_id * 3) % 15) ELSE 2 + ((d.invoice_id * 5) % 25) END,
  CASE WHEN s.line_no <= 2 THEN 100 + ((d.invoice_id * 19) % 150) ELSE 40 + ((d.invoice_id * 23) % 200) END,
  ROUND(
    (CASE WHEN s.line_no <= 2 THEN 3 + ((d.invoice_id * 3) % 15) ELSE 2 + ((d.invoice_id * 5) % 25) END)
    *
    (CASE WHEN s.line_no <= 2 THEN 100 + ((d.invoice_id * 19) % 150) ELSE 40 + ((d.invoice_id * 23) % 200) END)
  , 2),
  NULL
FROM all_docs d CROSS JOIN seq s
WHERE NOT EXISTS (SELECT 1 FROM invoice_item WHERE invoice_id = d.invoice_id AND id > 2880);

-- ============================================================================
-- 10. INVOICE ITEMS (SUB)  -- ~720 rows of CUSTOM notes
-- Every 5th invoice gets 2 sub-items attached to its first root item.
-- ids 100001..100720. CUSTOM rows: quantity=1, unit_price=0, total_price=0,
-- unit='' per the application invariant in DocumentService.buildItem.
-- ============================================================================

INSERT INTO invoice_item (
    id, invoice_id, parent_item_id, item_type, item_id, item_name, item_description,
    quantity, unit_price, total_price
)
WITH RECURSIVE seq(n, invoice_id, sub_index) AS (
  SELECT 1, 5, 1
  UNION ALL
  SELECT
    n + 1,
    invoice_id + CASE WHEN sub_index = 2 THEN 5 ELSE 0 END,
    CASE WHEN sub_index = 2 THEN 1 ELSE sub_index + 1 END
  FROM seq
  WHERE n < 720
)
SELECT
  100000 + n,
  seq.invoice_id,
  (SELECT MIN(ii.id) FROM invoice_item ii WHERE ii.invoice_id = seq.invoice_id AND ii.parent_item_id IS NULL),
  'CUSTOM', 0,
  CASE seq.sub_index WHEN 1 THEN 'Zusatzinfo zur Hauptposition' ELSE 'Optionale Hinweise' END,
  CASE seq.sub_index
    WHEN 1 THEN 'Zusätzliche Informationen und Lieferbedingungen zur Position.'
    WHEN 2 THEN 'Optional: Verpackungseinheit und Versandmodalitäten klären.'
  END,
  1, 0, 0
FROM seq
WHERE (SELECT MIN(ii.id) FROM invoice_item ii WHERE ii.invoice_id = seq.invoice_id AND ii.parent_item_id IS NULL) IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM invoice_item WHERE id = 100000 + n);

-- ============================================================================
-- 11. DOCUMENT HISTORY  -- ~3500 rows
-- The entries the application writes for the path each document took to its status:
--   every document           CREATED (offers / cost calculations: their number; invoice
--                            drafts have no number yet, InvoiceLifecycleService)
--   invoice, not a draft     ISSUED "Nummer RE-..." on the invoice date
--   invoice PAID (6)         PAYMENT_RECORDED "1.234,56 € am 15.01.2024" on the payment
--                            date, the same amount and date as the payment row (section 12)
--   invoice CANCELLED (8)    CANCELLED
--   offer / cost calculation STATUS_CHANGED "Entwurf → Gesendet", then "Gesendet → Angenommen" /
--                            "Gesendet → Abgelehnt", then "Angenommen → Umgewandelt"
-- performed_by and ip_address stay NULL, like rows written by the self-hosted application.
-- ============================================================================

INSERT INTO document_history (id, invoice_id, action, action_date, notes)
SELECT
  ROW_NUMBER() OVER (ORDER BY e.invoice_id, e.step),
  e.invoice_id, e.action, e.action_date, e.notes
FROM (
  SELECT inv.id AS invoice_id, 1 AS step, 'CREATED' AS action,
         CAST(inv.invoice_date AS DATE) + TIME '09:00:00' AS action_date,
         CASE WHEN inv.document_type_id IN (1, 4) THEN NULL ELSE inv.document_number END AS notes
  FROM invoice inv
  UNION ALL
  SELECT inv.id, 2, 'ISSUED',
         CAST(inv.invoice_date AS DATE) + TIME '10:15:00',
         CONCAT('Nummer ', inv.document_number)
  FROM invoice inv
  WHERE inv.document_type_id IN (1, 4) AND inv.document_status_id <> 1
  UNION ALL
  SELECT inv.id, 3, 'PAYMENT_RECORDED',
         COALESCE(inv.paid_date, CAST(inv.invoice_date AS DATE) + 14) + TIME '11:00:00',
         CONCAT(
           REPLACE(REPLACE(REPLACE(TO_CHAR(inv.total_price, 'FM999,999,990.00'),
             ',', '#'), '.', ','), '#', '.'),
           ' € am ',
           TO_CHAR(COALESCE(inv.paid_date, CAST(inv.invoice_date AS DATE) + 14), 'DD.MM.YYYY'))
  FROM invoice inv
  WHERE inv.document_type_id IN (1, 4) AND inv.document_status_id = 6
  UNION ALL
  SELECT inv.id, 3, 'CANCELLED',
         CAST(inv.invoice_date AS DATE) + 20 + TIME '14:00:00',
         NULL
  FROM invoice inv
  WHERE inv.document_type_id IN (1, 4) AND inv.document_status_id = 8
  UNION ALL
  SELECT inv.id, 3, 'STATUS_CHANGED',
         CAST(inv.invoice_date AS DATE) + 20 + TIME '14:00:00',
         'Gesendet → Umgewandelt'
  FROM invoice inv
  WHERE inv.document_type_id IN (1, 4) AND inv.document_status_id = 5
  UNION ALL
  SELECT inv.id, 2, 'STATUS_CHANGED',
         CAST(inv.invoice_date AS DATE) + 1 + TIME '10:15:00',
         'Entwurf → Gesendet'
  FROM invoice inv
  WHERE inv.document_type_id IN (2, 3) AND inv.document_status_id IN (2, 3, 4, 5)
  UNION ALL
  SELECT inv.id, 3, 'STATUS_CHANGED',
         CAST(inv.invoice_date AS DATE) + 7 + TIME '16:30:00',
         CASE WHEN inv.document_status_id = 4 THEN 'Gesendet → Abgelehnt'
              ELSE 'Gesendet → Angenommen' END
  FROM invoice inv
  WHERE inv.document_type_id IN (2, 3) AND inv.document_status_id IN (3, 4, 5)
  UNION ALL
  SELECT inv.id, 4, 'STATUS_CHANGED',
         CAST(inv.invoice_date AS DATE) + 10 + TIME '09:30:00',
         'Angenommen → Umgewandelt'
  FROM invoice inv
  WHERE inv.document_type_id IN (2, 3) AND inv.document_status_id = 5
) e;

-- ============================================================================
-- 12. PAYMENTS  -- ~400 rows for PAID invoices
-- One payment per PAID INVOICE/PARTED doc. For INVOICE with status 6
-- (50% of 700 = 350) and PARTED with status 6 (70% of 70 = 49) = ~399.
-- Plus a small fraction of partial payments.
-- ============================================================================

INSERT INTO payment (id, invoice_id, payment_date, amount, payment_method, reference, notes)
SELECT
  ROW_NUMBER() OVER (ORDER BY inv.id),
  inv.id,
  CASE
    WHEN inv.paid_date IS NOT NULL THEN inv.paid_date
    ELSE (CAST(inv.invoice_date AS DATE) + CAST(ROUND(14) AS INT))
  END,
  inv.total_price,
  (ARRAY['Banküberweisung', 'SEPA-Lastschrift', 'PayPal', 'Kreditkarte'])[1 + (inv.id % 4)],
  CONCAT('Zahlung ', inv.document_number),
  NULL
FROM invoice inv
WHERE inv.document_status_id = 6
  AND inv.document_type_id IN (1, 4)
  AND NOT EXISTS (SELECT 1 FROM payment WHERE payment.invoice_id = inv.id);

-- Identity columns were filled with explicit ids: move the sequences past them.
SELECT setval(pg_get_serial_sequence('owner', 'id'), (SELECT COALESCE(MAX(id), 1) FROM owner));
SELECT setval(pg_get_serial_sequence('customer', 'id'), (SELECT COALESCE(MAX(id), 1) FROM customer));
SELECT setval(pg_get_serial_sequence('equipment', 'id'), (SELECT COALESCE(MAX(id), 1) FROM equipment));
SELECT setval(pg_get_serial_sequence('article', 'id'), (SELECT COALESCE(MAX(id), 1) FROM article));
SELECT setval(pg_get_serial_sequence('service', 'id'), (SELECT COALESCE(MAX(id), 1) FROM service));
SELECT setval(pg_get_serial_sequence('invoice', 'id'), (SELECT COALESCE(MAX(id), 1) FROM invoice));
SELECT setval(pg_get_serial_sequence('invoice_period', 'id'), (SELECT COALESCE(MAX(id), 1) FROM invoice_period));
SELECT setval(pg_get_serial_sequence('invoice_item', 'id'), (SELECT COALESCE(MAX(id), 1) FROM invoice_item));
SELECT setval(pg_get_serial_sequence('document_history', 'id'), (SELECT COALESCE(MAX(id), 1) FROM document_history));
SELECT setval(pg_get_serial_sequence('payment', 'id'), (SELECT COALESCE(MAX(id), 1) FROM payment));
SELECT setval(pg_get_serial_sequence('document_number_series', 'id'), (SELECT COALESCE(MAX(id), 1) FROM document_number_series));
