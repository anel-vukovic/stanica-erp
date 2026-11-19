/* =====================================================================
   StanicaERP - 02 View-ovi i triggeri
   ===================================================================== */
USE StanicaERP;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* ---------- Iznosi po stavci dokumenta ----------
   RAC: cijena je MPC sa PDV-om, osnovica se izvlači iz nje.
   KAL / FAK: cijena je bez PDV-a, PDV se dodaje.
   NIV: iznos je razlika u vrijednosti zalihe (nova - stara MPC).          */
CREATE OR ALTER VIEW dbo.vw_StavkaIznos
AS
SELECT  s.StavkaId,
        s.DokumentId,
        d.Vrsta,
        d.Broj,
        d.Datum,
        d.ObjekatId,
        d.PartnerId,
        d.Status,
        s.ArtikalId,
        s.Kolicina,
        s.Cijena,
        s.RabatPosto,
        s.PdvStopa,
        s.NabCijena,
        bez.IznosBezPdv,
        sa.IznosSaPdv,
        CAST(sa.IznosSaPdv - bez.IznosBezPdv AS DECIMAL(18,2)) AS IznosPdv,
        CAST(s.Kolicina * COALESCE(s.NabCijena, CASE WHEN d.Vrsta = 'KAL' THEN s.Cijena END, 0)
             AS DECIMAL(18,2)) AS NabavnaVrijednost
FROM dbo.DokumentStavka AS s
JOIN dbo.Dokument       AS d ON d.DokumentId = s.DokumentId
CROSS APPLY (
    SELECT CAST(CASE d.Vrsta
                    WHEN 'RAC' THEN s.Kolicina * s.Cijena * (1 - s.RabatPosto / 100)
                    WHEN 'NIV' THEN s.Kolicina * (s.Cijena - ISNULL(s.StaraCijena, 0))
                END AS DECIMAL(18,2)) AS SaPdvDirektno
) AS dir
CROSS APPLY (
    SELECT CAST(CASE WHEN dir.SaPdvDirektno IS NOT NULL
                     THEN dir.SaPdvDirektno / (1 + s.PdvStopa / 100)
                     ELSE s.Kolicina * s.Cijena * (1 - s.RabatPosto / 100)
                END AS DECIMAL(18,2)) AS IznosBezPdv
) AS bez
CROSS APPLY (
    SELECT CAST(ISNULL(dir.SaPdvDirektno, bez.IznosBezPdv * (1 + s.PdvStopa / 100))
                AS DECIMAL(18,2)) AS IznosSaPdv
) AS sa;
GO

/* ---------- Stanje zaliha sa vrijednostima ---------- */
CREATE OR ALTER VIEW dbo.vw_LagerStanje
AS
SELECT  o.ObjekatId,
        o.Sifra                                   AS ObjekatSifra,
        o.Naziv                                   AS Objekat,
        o.Tip                                     AS ObjekatTip,
        a.ArtikalId,
        a.Sifra,
        a.Naziv,
        g.Naziv                                   AS Grupa,
        a.JedMjere,
        ISNULL(l.Kolicina, 0)                     AS Kolicina,
        a.MinZaliha,
        ISNULL(l.ProsjNabCijena, 0)               AS ProsjNabCijena,
        CAST(ISNULL(l.Kolicina, 0) * ISNULL(l.ProsjNabCijena, 0) AS DECIMAL(18,2)) AS NabavnaVrijednost,
        c.MPC,
        CAST(ISNULL(l.Kolicina, 0) * c.MPC AS DECIMAL(18,2))                       AS MaloprodajnaVrijednost,
        CAST(CASE WHEN o.Tip = 'MP' AND ISNULL(l.Kolicina, 0) < a.MinZaliha THEN 1 ELSE 0 END AS BIT) AS IspodMinimuma
FROM dbo.Objekat AS o
CROSS JOIN dbo.Artikal AS a
JOIN  dbo.Grupa  AS g ON g.GrupaId = a.GrupaId
LEFT JOIN dbo.Lager  AS l ON l.ObjekatId = o.ObjekatId AND l.ArtikalId = a.ArtikalId
LEFT JOIN dbo.Cijena AS c ON c.ObjekatId = o.ObjekatId AND c.ArtikalId = a.ArtikalId
WHERE a.Aktivan = 1
  AND (l.ArtikalId IS NOT NULL OR c.ArtikalId IS NOT NULL);
GO

/* ---------- Pregled dokumenata sa ukupnim iznosima ---------- */
CREATE OR ALTER VIEW dbo.vw_DokumentPregled
AS
SELECT  d.DokumentId,
        d.Vrsta,
        d.Broj,
        d.Datum,
        o.Naziv                                         AS Objekat,
        p.Naziv                                         AS Partner,
        CASE d.Status WHEN 'O' THEN N'Otvoren' ELSE N'Proknjižen' END AS Status,
        COUNT(s.StavkaId)                               AS Stavki,
        CAST(ISNULL(SUM(s.IznosBezPdv), 0) AS DECIMAL(18,2)) AS IznosBezPdv,
        CAST(ISNULL(SUM(s.IznosPdv), 0)    AS DECIMAL(18,2)) AS Pdv,
        CAST(ISNULL(SUM(s.IznosSaPdv), 0)  AS DECIMAL(18,2)) AS IznosSaPdv,
        d.Napomena,
        d.ObjekatId,
        d.PartnerId,
        d.Status                                        AS StatusKod
FROM dbo.Dokument AS d
JOIN dbo.Objekat  AS o ON o.ObjekatId = d.ObjekatId
LEFT JOIN dbo.Partner AS p ON p.PartnerId = d.PartnerId
LEFT JOIN dbo.vw_StavkaIznos AS s ON s.DokumentId = d.DokumentId
GROUP BY d.DokumentId, d.Vrsta, d.Broj, d.Datum, o.Naziv, p.Naziv, d.Status,
         d.Napomena, d.ObjekatId, d.PartnerId;
GO

/* =====================================================================
   TRIGGERI
   Svi su pisani set-based: jedan INSERT/UPDATE može dirati više redova,
   zato se radi s cijelim tabelama inserted/deleted, nikad s jednom vrijednošću.
   ===================================================================== */

-- 1) Stavke proknjiženog dokumenta su zaključane
CREATE OR ALTER TRIGGER dbo.trg_DokumentStavka_Zakljucano
ON dbo.DokumentStavka
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (
        SELECT 1
        FROM (SELECT DokumentId FROM inserted
              UNION
              SELECT DokumentId FROM deleted) AS x
        JOIN dbo.Dokument AS d ON d.DokumentId = x.DokumentId
        WHERE d.Status = 'P'
    )
        THROW 50010, N'Dokument je proknjižen i njegove stavke se ne mogu mijenjati.', 1;
END;
GO

-- 2) Proknjižen dokument se ne može obrisati, vratiti u "otvoren" ni prebaciti na drugi datum/objekat
CREATE OR ALTER TRIGGER dbo.trg_Dokument_Zastita
ON dbo.Dokument
AFTER UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    IF EXISTS (
        SELECT 1
        FROM deleted AS d
        LEFT JOIN inserted AS i ON i.DokumentId = d.DokumentId
        WHERE d.Status = 'P'
          AND (i.DokumentId IS NULL           -- brisanje
               OR i.Status    <> 'P'
               OR i.Datum     <> d.Datum
               OR i.ObjekatId <> d.ObjekatId)
    )
        THROW 50011, N'Proknjižen dokument se ne može brisati ni mijenjati.', 1;
END;
GO

-- 3) Revizijski trag: svaka promjena maloprodajne cijene ide u CijenaHistorija
CREATE OR ALTER TRIGGER dbo.trg_Cijena_Historija
ON dbo.Cijena
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    INSERT dbo.CijenaHistorija (ObjekatId, ArtikalId, StaraMPC, NovaMPC, Promijenjeno)
    SELECT i.ObjekatId, i.ArtikalId, d.MPC, i.MPC, i.VaziOd
    FROM inserted AS i
    LEFT JOIN deleted AS d ON d.ObjekatId = i.ObjekatId AND d.ArtikalId = i.ArtikalId
    WHERE d.MPC IS NULL OR d.MPC <> i.MPC;
END;
GO
