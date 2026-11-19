/* =====================================================================
   StanicaERP - 04 Testni podaci
   Simulira zadnjih 60 dana poslovanja: prijemi robe, dnevni promet na
   pumpama, veleprodajne fakture i promjene cijena goriva.
   Svi dokumenti se prave i knjiže kroz iste procedure koje koristi aplikacija.
   Svi nazivi, partneri i JIB-ovi su izmišljeni.
   ===================================================================== */
USE StanicaERP;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
SET NOCOUNT ON;

INSERT dbo.Objekat (Sifra, Naziv, Tip, Grad) VALUES
    (N'P01', N'Pumpa Sarajevo - Stup',           'MP', N'Sarajevo'),
    (N'P02', N'Pumpa Zenica - Centar',           'MP', N'Zenica'),
    (N'P03', N'Pumpa Mostar - Jug',              'MP', N'Mostar'),
    (N'VP1', N'Veleprodajno skladište Sarajevo', 'VP', N'Sarajevo');

INSERT dbo.Grupa (Naziv) VALUES (N'Gorivo'), (N'Maziva i auto-program'), (N'Shop');

INSERT dbo.Partner (Naziv, JIB, Tip, Grad, RabatPosto) VALUES
    (N'Rafinerija Adria d.o.o.',     '4200000000011', 'D', N'Brod',     0),
    (N'AutoChem d.o.o.',             '4200000000028', 'D', N'Sarajevo', 0),
    (N'Market Distribucija d.o.o.',  '4200000000035', 'D', N'Zenica',   0),
    (N'Trans-Bosna d.o.o.',          '4200000000042', 'K', N'Sarajevo', 3),
    (N'Gradski prevoz d.o.o.',       '4200000000059', 'K', N'Zenica',   5),
    (N'Gradnja Plus d.d.',           '4200000000066', 'K', N'Mostar',   2),
    (N'Agro Polje d.o.o.',           '4200000000073', 'K', N'Bihać',    1.5);

-- Parametri simulacije po artiklu
-- COLLATE DATABASE_DEFAULT: temp tabela je u tempdb, koji ima drugu collation od naše baze
CREATE TABLE #Art (
    Sifra NVARCHAR(20) COLLATE DATABASE_DEFAULT, Naziv NVARCHAR(100) COLLATE DATABASE_DEFAULT,
    Grupa NVARCHAR(50) COLLATE DATABASE_DEFAULT, JM NVARCHAR(5) COLLATE DATABASE_DEFAULT,
    MinZaliha DECIMAL(18,3), MPC DECIMAL(18,4), Nab DECIMAL(18,4), DobavljacJIB CHAR(13) COLLATE DATABASE_DEFAULT,
    Narudzba DECIMAL(18,3), DnevnoMin INT, DnevnoMax INT, VpMin DECIMAL(18,3), VpNarudzba DECIMAL(18,3)
);
INSERT #Art VALUES
 (N'G001', N'Eurodizel BS',                    N'Gorivo', N'L',   8000, 2.68, 1.98, '4200000000011', 20000, 900, 1700, 100000, 200000),
 (N'G002', N'Benzin BMB 95',                   N'Gorivo', N'L',   6000, 2.74, 2.02, '4200000000011', 15000, 700, 1300,  60000, 120000),
 (N'G003', N'Benzin BMB 100',                  N'Gorivo', N'L',   1500, 2.98, 2.20, '4200000000011',  5000,  80,  260,  15000,  30000),
 (N'G004', N'Autoplin LPG',                    N'Gorivo', N'L',   2500, 1.49, 1.02, '4200000000011',  8000, 200,  480,   NULL,   NULL),
 (N'M001', N'Motorno ulje 5W-30 1L',           N'Maziva i auto-program', N'kom', 12, 22.90, 13.50, '4200000000028',  48, 0,  4, NULL, NULL),
 (N'M002', N'Motorno ulje 5W-40 4L',           N'Maziva i auto-program', N'kom',  6, 74.90, 44.00, '4200000000028',  24, 0,  2, NULL, NULL),
 (N'M003', N'AdBlue 10L',                      N'Maziva i auto-program', N'kom', 10, 24.90, 13.20, '4200000000028',  40, 1,  5, NULL, NULL),
 (N'M004', N'Tečnost za brisače zimska 5L',    N'Maziva i auto-program', N'kom', 15, 11.90,  5.40, '4200000000028',  60, 2,  8, NULL, NULL),
 (N'M005', N'Antifriz G12 1L',                 N'Maziva i auto-program', N'kom',  8,  9.90,  4.80, '4200000000028',  30, 0,  3, NULL, NULL),
 (N'S001', N'Kafa za poneti',                  N'Shop', N'kom', 100, 2.00, 0.55, '4200000000035', 500, 30, 70, NULL, NULL),
 (N'S002', N'Voda 0,5 L',                      N'Shop', N'kom',  48, 1.50, 0.62, '4200000000035', 240, 10, 30, NULL, NULL),
 (N'S003', N'Energetski napitak 0,25 L',       N'Shop', N'kom',  24, 3.90, 1.95, '4200000000035', 120,  5, 18, NULL, NULL),
 (N'S004', N'Sendvič šunka-sir',               N'Shop', N'kom',  10, 4.90, 2.60, '4200000000035',  40,  4, 14, NULL, NULL),
 (N'S005', N'Čokoladica 50 g',                 N'Shop', N'kom',  30, 2.20, 1.05, '4200000000035', 120,  5, 15, NULL, NULL),
 (N'S006', N'Miris za auto',                   N'Shop', N'kom',   8, 5.90, 2.10, '4200000000035',  30,  0,  3, NULL, NULL);

INSERT dbo.Artikal (Sifra, Naziv, GrupaId, JedMjere, PdvStopa, MinZaliha)
SELECT t.Sifra, t.Naziv, g.GrupaId, t.JM, 17.00, t.MinZaliha
FROM #Art AS t JOIN dbo.Grupa AS g ON g.Naziv = t.Grupa;

DECLARE @Danas DATE = CAST(GETDATE() AS DATE);
DECLARE @Start DATE = DATEADD(DAY, -60, @Danas);

-- Početne maloprodajne cijene na svim pumpama
INSERT dbo.Cijena (ObjekatId, ArtikalId, MPC, VaziOd)
SELECT o.ObjekatId, a.ArtikalId, t.MPC, CAST(@Start AS DATETIME2(0))
FROM dbo.Objekat AS o
CROSS JOIN #Art AS t
JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
WHERE o.Tip = 'MP';

/* ---------- Simulacija dan po dan ---------- */
DECLARE @D DATE = @Start, @DokId INT, @Broj NVARCHAR(20), @Obj INT, @ObjSifra NVARCHAR(10),
        @Dob INT, @DobJIB CHAR(13), @Kupac INT, @Raz DECIMAL(18,2), @Bd INT,
        @Faktor DECIMAL(6,3), @DanUSedmici INT, @i INT, @j INT, @Art INT, @Kol DECIMAL(18,3);

DECLARE @VP INT = (SELECT ObjekatId FROM dbo.Objekat WHERE Sifra = N'VP1');
DECLARE @P02 INT = (SELECT ObjekatId FROM dbo.Objekat WHERE Sifra = N'P02');
DECLARE @G1 INT = (SELECT ArtikalId FROM dbo.Artikal WHERE Sifra = N'G001');
DECLARE @G2 INT = (SELECT ArtikalId FROM dbo.Artikal WHERE Sifra = N'G002');
DECLARE @G3 INT = (SELECT ArtikalId FROM dbo.Artikal WHERE Sifra = N'G003');
DECLARE @Rafinerija INT = (SELECT PartnerId FROM dbo.Partner WHERE JIB = '4200000000011');

DECLARE @MP TABLE (Rb INT IDENTITY, ObjekatId INT, Sifra NVARCHAR(10));
INSERT @MP (ObjekatId, Sifra) SELECT ObjekatId, Sifra FROM dbo.Objekat WHERE Tip = 'MP' ORDER BY ObjekatId;

DECLARE @Dobavljaci TABLE (Rb INT IDENTITY, PartnerId INT, JIB CHAR(13));
INSERT @Dobavljaci (PartnerId, JIB) SELECT PartnerId, JIB FROM dbo.Partner WHERE Tip = 'D' ORDER BY PartnerId;

WHILE @D <= @Danas
BEGIN
    -- 0 = ponedjeljak ... 6 = nedjelja (1900-01-01 je bio ponedjeljak)
    SET @DanUSedmici = DATEDIFF(DAY, '19000101', @D) % 7;

    /* --- Promjene cijena goriva na svim pumpama --- */
    IF @D = DATEADD(DAY, -41, @Danas)
    BEGIN
        EXEC dbo.usp_Nivelacija @ArtikalId = @G1, @NovaMPC = 2.72, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
        EXEC dbo.usp_Nivelacija @ArtikalId = @G2, @NovaMPC = 2.79, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
    END
    IF @D = DATEADD(DAY, -19, @Danas)
    BEGIN
        EXEC dbo.usp_Nivelacija @ArtikalId = @G1, @NovaMPC = 2.65, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
        EXEC dbo.usp_Nivelacija @ArtikalId = @G2, @NovaMPC = 2.76, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
    END
    IF @D = DATEADD(DAY, -6, @Danas)
    BEGIN
        EXEC dbo.usp_Nivelacija @ArtikalId = @G1, @NovaMPC = 2.69, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
        EXEC dbo.usp_Nivelacija @ArtikalId = @G3, @NovaMPC = 3.04, @Datum = @D, @Razlika = @Raz OUTPUT, @BrojDok = @Bd OUTPUT;
    END

    /* --- Pumpe --- */
    SET @i = 1;
    WHILE @i <= (SELECT COUNT(*) FROM @MP)
    BEGIN
        SELECT @Obj = ObjekatId, @ObjSifra = Sifra FROM @MP WHERE Rb = @i;

        -- Jutarnji prijem: naruči sve što je palo ispod 1,5 x minimum
        -- (u Zenici shop i maziva nisu naručivani zadnjih 15 dana -> upozorenja na dashboardu)
        SET @j = 1;
        WHILE @j <= (SELECT COUNT(*) FROM @Dobavljaci)
        BEGIN
            SELECT @Dob = PartnerId, @DobJIB = JIB FROM @Dobavljaci WHERE Rb = @j;

            IF EXISTS (
                SELECT 1 FROM #Art AS t
                JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
                LEFT JOIN dbo.Lager AS l ON l.ObjekatId = @Obj AND l.ArtikalId = a.ArtikalId
                WHERE t.DobavljacJIB = @DobJIB
                  AND ISNULL(l.Kolicina, 0) < a.MinZaliha * 1.5
                  AND NOT (@Obj = @P02 AND t.Grupa <> N'Gorivo' AND @D > DATEADD(DAY, -15, @Danas)))
            BEGIN
                EXEC dbo.usp_Dokument_Kreiraj @Vrsta = 'KAL', @Datum = @D, @ObjekatId = @Obj, @PartnerId = @Dob,
                     @Napomena = N'Prijem robe', @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;

                INSERT dbo.DokumentStavka (DokumentId, ArtikalId, Kolicina, Cijena, RabatPosto, PdvStopa)
                SELECT @DokId, a.ArtikalId, t.Narudzba,
                       ROUND(t.Nab * CASE WHEN t.Grupa = N'Gorivo'
                                          THEN 1 + ((ABS(CHECKSUM(NEWID(), t.Sifra)) % 7) - 3) / 100.0
                                          ELSE 1 END, 4),
                       0, a.PdvStopa
                FROM #Art AS t
                JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
                LEFT JOIN dbo.Lager AS l ON l.ObjekatId = @Obj AND l.ArtikalId = a.ArtikalId
                WHERE t.DobavljacJIB = @DobJIB
                  AND ISNULL(l.Kolicina, 0) < a.MinZaliha * 1.5
                  AND NOT (@Obj = @P02 AND t.Grupa <> N'Gorivo' AND @D > DATEADD(DAY, -15, @Danas));

                EXEC dbo.usp_Dokument_Proknjizi @DokumentId = @DokId;
            END
            SET @j += 1;
        END

        -- Dnevni promet: vikend jači, Sarajevo najprometnije, današnji dan još traje
        SET @Faktor = CASE @DanUSedmici WHEN 4 THEN 1.25 WHEN 5 THEN 1.15 WHEN 6 THEN 0.80 ELSE 1.0 END
                    * CASE @ObjSifra WHEN N'P01' THEN 1.3 WHEN N'P03' THEN 0.9 ELSE 1.0 END
                    * CASE WHEN @D = @Danas THEN 0.6 ELSE 1.0 END;

        EXEC dbo.usp_Dokument_Kreiraj @Vrsta = 'RAC', @Datum = @D, @ObjekatId = @Obj,
             @Napomena = N'Zbirni dnevni promet', @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;

        INSERT dbo.DokumentStavka (DokumentId, ArtikalId, Kolicina, Cijena, RabatPosto, PdvStopa)
        SELECT @DokId, a.ArtikalId, q.Kol, c.MPC, 0, a.PdvStopa
        FROM #Art AS t
        JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
        JOIN dbo.Cijena  AS c ON c.ObjekatId = @Obj AND c.ArtikalId = a.ArtikalId
        JOIN dbo.Lager   AS l ON l.ObjekatId = @Obj AND l.ArtikalId = a.ArtikalId
        CROSS APPLY (
            SELECT CAST(ROUND((t.DnevnoMin + ABS(CHECKSUM(NEWID(), t.Sifra)) % (t.DnevnoMax - t.DnevnoMin + 1)
                        + CASE WHEN t.Grupa = N'Gorivo' THEN (ABS(CHECKSUM(NEWID(), t.Naziv)) % 100) / 100.0 ELSE 0 END)
                        * @Faktor, CASE WHEN t.Grupa = N'Gorivo' THEN 2 ELSE 0 END) AS DECIMAL(18,3)) AS Trazeno
        ) AS r
        CROSS APPLY (SELECT CASE WHEN r.Trazeno > l.Kolicina THEN l.Kolicina ELSE r.Trazeno END AS Kol) AS q
        WHERE q.Kol > 0;

        IF @@ROWCOUNT = 0
            EXEC dbo.usp_Dokument_Obrisi @DokumentId = @DokId;
        ELSE
            EXEC dbo.usp_Dokument_Proknjizi @DokumentId = @DokId;

        SET @i += 1;
    END

    /* --- Veleprodaja --- */
    IF EXISTS (
        SELECT 1 FROM #Art AS t JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
        LEFT JOIN dbo.Lager AS l ON l.ObjekatId = @VP AND l.ArtikalId = a.ArtikalId
        WHERE t.VpMin IS NOT NULL AND ISNULL(l.Kolicina, 0) < t.VpMin)
    BEGIN
        EXEC dbo.usp_Dokument_Kreiraj @Vrsta = 'KAL', @Datum = @D, @ObjekatId = @VP, @PartnerId = @Rafinerija,
             @Napomena = N'Prijem autocisternom', @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;

        INSERT dbo.DokumentStavka (DokumentId, ArtikalId, Kolicina, Cijena, RabatPosto, PdvStopa)
        SELECT @DokId, a.ArtikalId, t.VpNarudzba,
               ROUND(t.Nab * (0.98 + ((ABS(CHECKSUM(NEWID(), t.Sifra)) % 5) - 2) / 100.0), 4), 0, a.PdvStopa
        FROM #Art AS t JOIN dbo.Artikal AS a ON a.Sifra = t.Sifra
        LEFT JOIN dbo.Lager AS l ON l.ObjekatId = @VP AND l.ArtikalId = a.ArtikalId
        WHERE t.VpMin IS NOT NULL AND ISNULL(l.Kolicina, 0) < t.VpMin;

        EXEC dbo.usp_Dokument_Proknjizi @DokumentId = @DokId;
    END

    -- Faktura kupcu svaki drugi radni dan
    IF @DanUSedmici < 5 AND DATEDIFF(DAY, @Start, @D) % 2 = 1
    BEGIN
        SELECT TOP (1) @Kupac = PartnerId FROM dbo.Partner WHERE Tip = 'K' ORDER BY NEWID();

        EXEC dbo.usp_Dokument_Kreiraj @Vrsta = 'FAK', @Datum = @D, @ObjekatId = @VP, @PartnerId = @Kupac,
             @Napomena = N'Isporuka goriva', @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;

        SET @Kol = 3000 + ABS(CHECKSUM(NEWID())) % 6000;
        EXEC dbo.usp_Stavka_Dodaj @DokumentId = @DokId, @ArtikalId = @G1, @Kolicina = @Kol;

        IF ABS(CHECKSUM(NEWID())) % 2 = 0
        BEGIN
            SET @Kol = 1000 + ABS(CHECKSUM(NEWID())) % 3000;
            EXEC dbo.usp_Stavka_Dodaj @DokumentId = @DokId, @ArtikalId = @G2, @Kolicina = @Kol;
        END

        EXEC dbo.usp_Dokument_Proknjizi @DokumentId = @DokId;
    END

    SET @D = DATEADD(DAY, 1, @D);
END

/* --- Jedan otvoren dokument za demo: kalkulacija koja čeka knjiženje --- */
SET @Obj = (SELECT ObjekatId FROM dbo.Objekat WHERE Sifra = N'P02');
SET @Dob = (SELECT PartnerId FROM dbo.Partner WHERE JIB = '4200000000028');
EXEC dbo.usp_Dokument_Kreiraj @Vrsta = 'KAL', @Datum = @Danas, @ObjekatId = @Obj, @PartnerId = @Dob,
     @Napomena = N'Čeka provjeru količina', @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;
SET @Art = (SELECT ArtikalId FROM dbo.Artikal WHERE Sifra = N'M003');
EXEC dbo.usp_Stavka_Dodaj @DokumentId = @DokId, @ArtikalId = @Art, @Kolicina = 40, @Cijena = 13.20;
SET @Art = (SELECT ArtikalId FROM dbo.Artikal WHERE Sifra = N'M004');
EXEC dbo.usp_Stavka_Dodaj @DokumentId = @DokId, @ArtikalId = @Art, @Kolicina = 60, @Cijena = 5.40;

DROP TABLE #Art;

SELECT Vrsta, COUNT(*) AS Dokumenata FROM dbo.Dokument GROUP BY Vrsta ORDER BY Vrsta;
GO
