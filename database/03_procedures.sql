/* =====================================================================
   StanicaERP - 03 Stored procedure
   Sva poslovna pravila žive ovdje, ne u aplikaciji: bilo koji klijent
   (desktop, web, kasa) dobija ista pravila i istu konzistentnost.
   ===================================================================== */
USE StanicaERP;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* ---------- Novi dokument + atomarno dodjeljivanje broja ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Dokument_Kreiraj
    @Vrsta      CHAR(3),
    @Datum      DATE,
    @ObjekatId  INT,
    @PartnerId  INT           = NULL,
    @Napomena   NVARCHAR(200) = NULL,
    @DokumentId INT           OUTPUT,
    @Broj       NVARCHAR(20)  OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @TipObjekta CHAR(2) = (SELECT Tip FROM dbo.Objekat WHERE ObjekatId = @ObjekatId);

    IF @TipObjekta IS NULL
        THROW 50001, N'Objekat ne postoji.', 1;
    IF @Vrsta IN ('RAC', 'NIV') AND @TipObjekta <> 'MP'
        THROW 50002, N'Maloprodajni račun i nivelacija rade se samo na pumpi (MP objekat).', 1;
    IF @Vrsta = 'FAK' AND @TipObjekta <> 'VP'
        THROW 50003, N'Veleprodajna faktura izdaje se samo iz veleprodajnog skladišta.', 1;
    IF @Vrsta IN ('KAL', 'FAK') AND @PartnerId IS NULL
        THROW 50004, N'Kalkulacija i faktura moraju imati partnera.', 1;

    DECLARE @Godina INT = YEAR(@Datum), @Rb INT;

    BEGIN TRAN;
        -- UPDLOCK + HOLDLOCK: dva korisnika istovremeno nikad ne dobiju isti broj
        UPDATE dbo.Brojac WITH (UPDLOCK, HOLDLOCK)
           SET @Rb = Zadnji = Zadnji + 1
         WHERE Vrsta = @Vrsta AND Godina = @Godina;

        IF @@ROWCOUNT = 0
        BEGIN
            SET @Rb = 1;
            INSERT dbo.Brojac (Vrsta, Godina, Zadnji) VALUES (@Vrsta, @Godina, 1);
        END

        SET @Broj = CONCAT(@Vrsta, '-', @Godina, '-', RIGHT(CONCAT('00000', @Rb), 5));

        INSERT dbo.Dokument (Vrsta, Broj, Datum, ObjekatId, PartnerId, Napomena)
        VALUES (@Vrsta, @Broj, @Datum, @ObjekatId, @PartnerId, @Napomena);

        SET @DokumentId = SCOPE_IDENTITY();
    COMMIT;
END;
GO

/* ---------- Dodavanje stavke (cijena i rabat se povlače automatski) ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Stavka_Dodaj
    @DokumentId INT,
    @ArtikalId  INT,
    @Kolicina   DECIMAL(18,3),
    @Cijena     DECIMAL(18,4) = NULL,
    @RabatPosto DECIMAL(5,2)  = NULL
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @Vrsta CHAR(3), @Status CHAR(1), @ObjekatId INT, @PartnerId INT, @Pdv DECIMAL(5,2);

    SELECT @Vrsta = Vrsta, @Status = Status, @ObjekatId = ObjekatId, @PartnerId = PartnerId
    FROM dbo.Dokument
    WHERE DokumentId = @DokumentId;

    IF @Vrsta IS NULL
        THROW 50020, N'Dokument ne postoji.', 1;
    IF @Status <> 'O'
        THROW 50021, N'Dokument je proknjižen, stavke se ne mogu dodavati.', 1;
    IF @Vrsta = 'NIV'
        THROW 50022, N'Nivelacija se radi preko usp_Nivelacija.', 1;
    IF @Kolicina IS NULL OR @Kolicina <= 0
        THROW 50023, N'Količina mora biti veća od nule.', 1;

    SELECT @Pdv = PdvStopa FROM dbo.Artikal WHERE ArtikalId = @ArtikalId AND Aktivan = 1;
    IF @Pdv IS NULL
        THROW 50024, N'Artikal ne postoji ili nije aktivan.', 1;

    IF @Cijena IS NULL
    BEGIN
        IF @Vrsta = 'RAC'          -- važeća maloprodajna cijena na toj pumpi
            SELECT @Cijena = MPC FROM dbo.Cijena WHERE ObjekatId = @ObjekatId AND ArtikalId = @ArtikalId;
        ELSE IF @Vrsta = 'FAK'     -- veleprodajna cijena = prosječna nabavna + 8 % marže
            SELECT @Cijena = ROUND(ProsjNabCijena * 1.08, 4)
            FROM dbo.Lager WHERE ObjekatId = @ObjekatId AND ArtikalId = @ArtikalId AND ProsjNabCijena > 0;

        IF @Cijena IS NULL
            THROW 50025, N'Cijena nije definisana za ovaj artikal u ovom objektu. Unesite je ručno.', 1;
    END

    IF @Cijena < 0
        THROW 50026, N'Cijena ne može biti negativna.', 1;

    -- Kupac u veleprodaji automatski dobija svoj ugovoreni rabat
    IF @RabatPosto IS NULL
        SET @RabatPosto = CASE WHEN @Vrsta = 'FAK'
                               THEN ISNULL((SELECT RabatPosto FROM dbo.Partner WHERE PartnerId = @PartnerId), 0)
                               ELSE 0 END;

    INSERT dbo.DokumentStavka (DokumentId, ArtikalId, Kolicina, Cijena, RabatPosto, PdvStopa)
    VALUES (@DokumentId, @ArtikalId, @Kolicina, @Cijena, @RabatPosto, @Pdv);

    SELECT CAST(SCOPE_IDENTITY() AS INT) AS StavkaId;
END;
GO

/* ---------- Brisanje stavke / otvorenog dokumenta ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Stavka_Obrisi
    @StavkaId INT
AS
BEGIN
    SET NOCOUNT ON;
    DELETE dbo.DokumentStavka WHERE StavkaId = @StavkaId;   -- trigger blokira ako je dokument proknjižen
END;
GO

CREATE OR ALTER PROCEDURE dbo.usp_Dokument_Obrisi
    @DokumentId INT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;
        DELETE dbo.DokumentStavka WHERE DokumentId = @DokumentId;
        DELETE dbo.Dokument       WHERE DokumentId = @DokumentId;
    COMMIT;
END;
GO

/* =====================================================================
   KNJIŽENJE DOKUMENTA - srce sistema
   Sve u jednoj transakciji: ili se ažurira i lager i kartica i status,
   ili se ne mijenja ništa.
   ===================================================================== */
CREATE OR ALTER PROCEDURE dbo.usp_Dokument_Proknjizi
    @DokumentId INT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    BEGIN TRY
        BEGIN TRAN;

        DECLARE @Vrsta CHAR(3), @Status CHAR(1), @ObjekatId INT, @Datum DATE;

        -- UPDLOCK: niko drugi ne može knjižiti isti dokument u isto vrijeme
        SELECT @Vrsta = Vrsta, @Status = Status, @ObjekatId = ObjekatId, @Datum = Datum
        FROM dbo.Dokument WITH (UPDLOCK, ROWLOCK)
        WHERE DokumentId = @DokumentId;

        IF @Vrsta IS NULL
            THROW 50030, N'Dokument ne postoji.', 1;
        IF @Status = 'P'
            THROW 50031, N'Dokument je već proknjižen.', 1;
        IF NOT EXISTS (SELECT 1 FROM dbo.DokumentStavka WHERE DokumentId = @DokumentId)
            THROW 50032, N'Dokument nema nijednu stavku.', 1;

        -- Isti artikal može biti u više redova dokumenta, zato grupišemo
        DECLARE @Promjene TABLE (
            ArtikalId  INT PRIMARY KEY,
            Kolicina   DECIMAL(18,3),
            Vrijednost DECIMAL(18,4)
        );
        INSERT @Promjene (ArtikalId, Kolicina, Vrijednost)
        SELECT ArtikalId, SUM(Kolicina), SUM(Kolicina * Cijena * (1 - RabatPosto / 100))
        FROM dbo.DokumentStavka
        WHERE DokumentId = @DokumentId
        GROUP BY ArtikalId;

        IF @Vrsta = 'KAL'
        BEGIN
            /* Ulaz robe: količina raste, nabavna cijena se preračunava kao ponderisani prosjek.
               Napomena: u UPDATE SET-u svi izrazi vide STARE vrijednosti reda,
               pa ProsjNabCijena računa sa količinom prije ulaza.                         */
            MERGE dbo.Lager WITH (HOLDLOCK) AS l
            USING (SELECT ArtikalId, Kolicina, Vrijednost FROM @Promjene) AS p
               ON l.ObjekatId = @ObjekatId AND l.ArtikalId = p.ArtikalId
            WHEN MATCHED THEN
                UPDATE SET ProsjNabCijena = CASE WHEN l.Kolicina + p.Kolicina = 0 THEN l.ProsjNabCijena
                                                 ELSE (l.Kolicina * l.ProsjNabCijena + p.Vrijednost)
                                                      / (l.Kolicina + p.Kolicina) END,
                           Kolicina       = l.Kolicina + p.Kolicina
            WHEN NOT MATCHED THEN
                INSERT (ObjekatId, ArtikalId, Kolicina, ProsjNabCijena)
                VALUES (@ObjekatId, p.ArtikalId, p.Kolicina,
                        CASE WHEN p.Kolicina = 0 THEN 0 ELSE p.Vrijednost / p.Kolicina END);

            INSERT dbo.LagerPromet (ObjekatId, ArtikalId, DokumentId, Datum, Ulaz, NabCijena)
            SELECT @ObjekatId, ArtikalId, @DokumentId, @Datum, Kolicina,
                   CASE WHEN Kolicina = 0 THEN 0 ELSE Vrijednost / Kolicina END
            FROM @Promjene;
        END
        ELSE IF @Vrsta IN ('RAC', 'FAK')
        BEGIN
            -- 1) Provjera zalihe za sve artikle odjednom, sa jasnom porukom šta fali
            DECLARE @Nedostaje NVARCHAR(MAX);

            SELECT @Nedostaje = STRING_AGG(
                       CONCAT(a.Naziv, N' (traženo ', CAST(p.Kolicina AS DECIMAL(18,2)),
                              N' ', a.JedMjere, N', na stanju ', CAST(ISNULL(l.Kolicina, 0) AS DECIMAL(18,2)), N')'),
                       N'; ')
            FROM @Promjene AS p
            JOIN dbo.Artikal AS a ON a.ArtikalId = p.ArtikalId
            LEFT JOIN dbo.Lager AS l WITH (UPDLOCK) ON l.ObjekatId = @ObjekatId AND l.ArtikalId = p.ArtikalId
            WHERE p.Kolicina > ISNULL(l.Kolicina, 0);

            IF @Nedostaje IS NOT NULL
            BEGIN
                DECLARE @Poruka NVARCHAR(2048) = LEFT(N'Nema dovoljno robe na stanju: ' + @Nedostaje, 2048);
                THROW 50033, @Poruka, 1;
            END

            -- 2) Zapamti nabavnu cijenu u trenutku prodaje (za razliku u cijeni / RUC)
            UPDATE s
               SET NabCijena = l.ProsjNabCijena
            FROM dbo.DokumentStavka AS s
            JOIN dbo.Lager AS l ON l.ObjekatId = @ObjekatId AND l.ArtikalId = s.ArtikalId
            WHERE s.DokumentId = @DokumentId;

            -- 3) Kartica artikla
            INSERT dbo.LagerPromet (ObjekatId, ArtikalId, DokumentId, Datum, Izlaz, NabCijena)
            SELECT @ObjekatId, p.ArtikalId, @DokumentId, @Datum, p.Kolicina, l.ProsjNabCijena
            FROM @Promjene AS p
            JOIN dbo.Lager AS l ON l.ObjekatId = @ObjekatId AND l.ArtikalId = p.ArtikalId;

            -- 4) Skidanje sa zalihe
            UPDATE l
               SET Kolicina = l.Kolicina - p.Kolicina
            FROM dbo.Lager AS l
            JOIN @Promjene AS p ON p.ArtikalId = l.ArtikalId
            WHERE l.ObjekatId = @ObjekatId;
        END
        ELSE IF @Vrsta = 'NIV'
        BEGIN
            -- Nivelacija mijenja maloprodajnu cijenu; trigger upisuje historiju
            MERGE dbo.Cijena AS c
            USING (SELECT ArtikalId, Cijena FROM dbo.DokumentStavka WHERE DokumentId = @DokumentId) AS s
               ON c.ObjekatId = @ObjekatId AND c.ArtikalId = s.ArtikalId
            WHEN MATCHED THEN
                UPDATE SET MPC = s.Cijena, VaziOd = CAST(@Datum AS DATETIME2(0))
            WHEN NOT MATCHED THEN
                INSERT (ObjekatId, ArtikalId, MPC, VaziOd)
                VALUES (@ObjekatId, s.ArtikalId, s.Cijena, CAST(@Datum AS DATETIME2(0)));
        END

        UPDATE dbo.Dokument
           SET Status = 'P', Proknjizeno = SYSDATETIME()
         WHERE DokumentId = @DokumentId;

        COMMIT;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK;
        THROW;   -- originalna greška ide dalje do aplikacije
    END CATCH
END;
GO

/* ---------- Nivelacija: nova MPC na jednoj ili svim pumpama ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Nivelacija
    @ArtikalId   INT,
    @NovaMPC     DECIMAL(18,4),
    @ObjekatId   INT  = NULL,          -- NULL = sve pumpe
    @Datum       DATE = NULL,
    @Razlika     DECIMAL(18,2) OUTPUT, -- promjena vrijednosti zalihe (sa PDV-om)
    @BrojDok     INT           OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    IF @NovaMPC IS NULL OR @NovaMPC <= 0
        THROW 50040, N'Nova cijena mora biti veća od nule.', 1;

    SET @Datum   = ISNULL(@Datum, CAST(GETDATE() AS DATE));
    SET @Razlika = 0;
    SET @BrojDok = 0;

    DECLARE @Pdv DECIMAL(5,2) = (SELECT PdvStopa FROM dbo.Artikal WHERE ArtikalId = @ArtikalId);
    IF @Pdv IS NULL
        THROW 50041, N'Artikal ne postoji.', 1;

    -- Pumpe na kojima se cijena zaista mijenja
    DECLARE @Pumpe TABLE (Rb INT IDENTITY, ObjekatId INT, StaraMPC DECIMAL(18,4), Kolicina DECIMAL(18,3));
    INSERT @Pumpe (ObjekatId, StaraMPC, Kolicina)
    SELECT o.ObjekatId, c.MPC, ISNULL(l.Kolicina, 0)
    FROM dbo.Objekat AS o
    JOIN dbo.Cijena  AS c ON c.ObjekatId = o.ObjekatId AND c.ArtikalId = @ArtikalId
    LEFT JOIN dbo.Lager AS l ON l.ObjekatId = o.ObjekatId AND l.ArtikalId = @ArtikalId
    WHERE o.Tip = 'MP'
      AND (@ObjekatId IS NULL OR o.ObjekatId = @ObjekatId)
      AND c.MPC <> @NovaMPC;

    IF NOT EXISTS (SELECT 1 FROM @Pumpe)
        THROW 50042, N'Nema promjene: cijena je već ista ili artikal nema cijenu na izabranoj pumpi.', 1;

    BEGIN TRY
        BEGIN TRAN;

        DECLARE @i INT = 1, @n INT = (SELECT COUNT(*) FROM @Pumpe);
        DECLARE @Obj INT, @Stara DECIMAL(18,4), @Kol DECIMAL(18,3), @DokId INT, @Broj NVARCHAR(20);

        WHILE @i <= @n
        BEGIN
            SELECT @Obj = ObjekatId, @Stara = StaraMPC, @Kol = Kolicina FROM @Pumpe WHERE Rb = @i;

            EXEC dbo.usp_Dokument_Kreiraj
                 @Vrsta = 'NIV', @Datum = @Datum, @ObjekatId = @Obj,
                 @Napomena = N'Promjena maloprodajne cijene',
                 @DokumentId = @DokId OUTPUT, @Broj = @Broj OUTPUT;

            INSERT dbo.DokumentStavka (DokumentId, ArtikalId, Kolicina, Cijena, StaraCijena, PdvStopa)
            VALUES (@DokId, @ArtikalId, @Kol, @NovaMPC, @Stara, @Pdv);

            EXEC dbo.usp_Dokument_Proknjizi @DokumentId = @DokId;

            SET @Razlika += ROUND(@Kol * (@NovaMPC - @Stara), 2);
            SET @BrojDok += 1;
            SET @i += 1;
        END

        COMMIT;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK;
        THROW;
    END CATCH
END;
GO

/* ---------- Novi artikal ili izmjena postojećeg ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Artikal_Sacuvaj
    @ArtikalId INT OUTPUT,            -- NULL = novi
    @Sifra     NVARCHAR(20),
    @Naziv     NVARCHAR(100),
    @GrupaId   INT,
    @JedMjere  NVARCHAR(5),
    @PdvStopa  DECIMAL(5,2),
    @MinZaliha DECIMAL(18,3),
    @Aktivan   BIT,
    @MPC       DECIMAL(18,4) = NULL   -- za novi artikal: početna cijena na svim pumpama
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    SET @Sifra = LTRIM(RTRIM(@Sifra));
    SET @Naziv = LTRIM(RTRIM(@Naziv));

    IF @Sifra = N'' OR @Naziv = N''
        THROW 50050, N'Šifra i naziv su obavezni.', 1;
    IF EXISTS (SELECT 1 FROM dbo.Artikal WHERE Sifra = @Sifra AND ArtikalId <> ISNULL(@ArtikalId, -1))
        THROW 50051, N'Artikal sa tom šifrom već postoji.', 1;

    BEGIN TRAN;
        IF @ArtikalId IS NULL
        BEGIN
            INSERT dbo.Artikal (Sifra, Naziv, GrupaId, JedMjere, PdvStopa, MinZaliha, Aktivan)
            VALUES (@Sifra, @Naziv, @GrupaId, @JedMjere, @PdvStopa, @MinZaliha, @Aktivan);
            SET @ArtikalId = SCOPE_IDENTITY();

            IF @MPC IS NOT NULL AND @MPC > 0
                INSERT dbo.Cijena (ObjekatId, ArtikalId, MPC)
                SELECT ObjekatId, @ArtikalId, @MPC FROM dbo.Objekat WHERE Tip = 'MP';
        END
        ELSE
        BEGIN
            UPDATE dbo.Artikal
               SET Sifra = @Sifra, Naziv = @Naziv, GrupaId = @GrupaId, JedMjere = @JedMjere,
                   PdvStopa = @PdvStopa, MinZaliha = @MinZaliha, Aktivan = @Aktivan
             WHERE ArtikalId = @ArtikalId;

            IF @@ROWCOUNT = 0
                THROW 50052, N'Artikal ne postoji.', 1;
        END
    COMMIT;
END;
GO

/* ---------- Kartica artikla sa tekućim saldom (window funkcija) ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_KarticaArtikla
    @ObjekatId INT,
    @ArtikalId INT,
    @Od        DATE,
    @Do        DATE
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @Pocetno DECIMAL(18,3) = ISNULL((
        SELECT SUM(Ulaz - Izlaz)
        FROM dbo.LagerPromet
        WHERE ObjekatId = @ObjekatId AND ArtikalId = @ArtikalId AND Datum < @Od), 0);

    WITH Promet AS (
        SELECT CAST(NULL AS DATE) AS Datum, CAST(NULL AS NVARCHAR(20)) AS Dokument,
               CAST(N'Početno stanje' AS NVARCHAR(150)) AS Opis,
               CAST(0 AS DECIMAL(18,3)) AS Ulaz, CAST(0 AS DECIMAL(18,3)) AS Izlaz,
               CAST(NULL AS DECIMAL(18,4)) AS NabCijena, CAST(0 AS BIGINT) AS Redoslijed
        UNION ALL
        SELECT p.Datum, d.Broj,
               CAST(CASE d.Vrsta WHEN 'KAL' THEN N'Ulaz - ' + ISNULL(pa.Naziv, N'')
                                 WHEN 'RAC' THEN N'Maloprodaja'
                                 WHEN 'FAK' THEN N'Veleprodaja - ' + ISNULL(pa.Naziv, N'')
                    END AS NVARCHAR(150)),
               p.Ulaz, p.Izlaz, p.NabCijena, p.PrometId
        FROM dbo.LagerPromet AS p
        JOIN dbo.Dokument    AS d  ON d.DokumentId = p.DokumentId
        LEFT JOIN dbo.Partner AS pa ON pa.PartnerId = d.PartnerId
        WHERE p.ObjekatId = @ObjekatId AND p.ArtikalId = @ArtikalId
          AND p.Datum BETWEEN @Od AND @Do
    )
    SELECT Datum, Dokument, Opis, Ulaz, Izlaz,
           CAST(@Pocetno + SUM(Ulaz - Izlaz) OVER (ORDER BY Redoslijed ROWS UNBOUNDED PRECEDING)
                AS DECIMAL(18,3)) AS Stanje,
           NabCijena
    FROM Promet
    ORDER BY Redoslijed;
END;
GO

/* ---------- Izvještaj prodaje sa razlikom u cijeni (RUC) ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Izvjestaj_Prodaja
    @Od        DATE,
    @Do        DATE,
    @ObjekatId INT = NULL      -- NULL = svi objekti
AS
BEGIN
    SET NOCOUNT ON;

    SELECT  CASE WHEN GROUPING(a.Sifra) = 1 THEN N'UKUPNO' ELSE g.Naziv END AS Grupa,
            a.Sifra,
            a.Naziv                                   AS Artikal,
            a.JedMjere                                AS JM,
            CASE WHEN GROUPING(a.Sifra) = 1 THEN NULL ELSE SUM(s.Kolicina) END AS Kolicina,
            SUM(s.IznosBezPdv)                        AS ProdajaBezPdv,
            SUM(s.IznosPdv)                           AS Pdv,
            SUM(s.IznosSaPdv)                         AS ProdajaSaPdv,
            SUM(s.NabavnaVrijednost)                  AS NabavnaVrijednost,
            SUM(s.IznosBezPdv - s.NabavnaVrijednost)  AS RUC,
            CAST(100.0 * SUM(s.IznosBezPdv - s.NabavnaVrijednost)
                 / NULLIF(SUM(s.IznosBezPdv), 0) AS DECIMAL(6,2)) AS RucPosto
    FROM dbo.vw_StavkaIznos AS s
    JOIN dbo.Artikal AS a ON a.ArtikalId = s.ArtikalId
    JOIN dbo.Grupa   AS g ON g.GrupaId  = a.GrupaId
    WHERE s.Vrsta IN ('RAC', 'FAK')
      AND s.Status = 'P'
      AND s.Datum BETWEEN @Od AND @Do
      AND (@ObjekatId IS NULL OR s.ObjekatId = @ObjekatId)
    GROUP BY GROUPING SETS ((g.Naziv, a.Sifra, a.Naziv, a.JedMjere), ())
    ORDER BY GROUPING(a.Sifra), g.Naziv, ProdajaSaPdv DESC
    OPTION (RECOMPILE);   -- "catch-all" filter: plan se pravi za stvarne parametre
END;
GO

/* ---------- Dashboard: KPI, promet po danima, artikli ispod minimuma ---------- */
CREATE OR ALTER PROCEDURE dbo.usp_Pregled
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @Danas DATE = CAST(GETDATE() AS DATE);
    DECLARE @PocetakMj DATE = DATEFROMPARTS(YEAR(@Danas), MONTH(@Danas), 1);

    -- 1) KPI
    SELECT
        ISNULL(SUM(CASE WHEN Datum = @Danas THEN IznosSaPdv END), 0)                           AS PrometDanas,
        ISNULL(SUM(CASE WHEN Datum >= @PocetakMj THEN IznosSaPdv END), 0)                      AS PrometMjesec,
        ISNULL(SUM(CASE WHEN Datum >= @PocetakMj THEN IznosBezPdv - NabavnaVrijednost END), 0) AS RucMjesec,
        (SELECT ISNULL(SUM(NabavnaVrijednost), 0) FROM dbo.vw_LagerStanje)                     AS VrijednostZaliha,
        (SELECT COUNT(*) FROM dbo.vw_LagerStanje WHERE IspodMinimuma = 1)                      AS IspodMinimuma,
        (SELECT COUNT(*) FROM dbo.Dokument WHERE Status = 'O')                                 AS OtvorenihDokumenata
    FROM dbo.vw_StavkaIznos
    WHERE Vrsta IN ('RAC', 'FAK') AND Status = 'P' AND Datum >= DATEADD(DAY, -40, @Danas);

    -- 2) Promet zadnjih 30 dana; GENERATE_SERIES (SQL Server 2022) daje i dane bez prometa
    SELECT  DATEADD(DAY, gs.value, DATEADD(DAY, -29, @Danas)) AS Datum,
            ISNULL(SUM(CASE WHEN s.Vrsta = 'RAC' THEN s.IznosSaPdv END), 0) AS Maloprodaja,
            ISNULL(SUM(CASE WHEN s.Vrsta = 'FAK' THEN s.IznosSaPdv END), 0) AS Veleprodaja
    FROM GENERATE_SERIES(0, 29) AS gs
    LEFT JOIN dbo.vw_StavkaIznos AS s
           ON s.Datum = DATEADD(DAY, gs.value, DATEADD(DAY, -29, @Danas))
          AND s.Vrsta IN ('RAC', 'FAK') AND s.Status = 'P'
    GROUP BY gs.value
    ORDER BY gs.value;

    -- 3) Šta treba naručiti
    SELECT Objekat, Sifra, Naziv, JedMjere AS JM, Kolicina, MinZaliha,
           MinZaliha - Kolicina AS Nedostaje
    FROM dbo.vw_LagerStanje
    WHERE IspodMinimuma = 1
    ORDER BY Objekat, Naziv;
END;
GO
