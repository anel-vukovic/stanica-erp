/* =====================================================================
   StanicaERP - 01 Šema baze
   Mini ERP za mrežu benzinskih pumpi + veleprodajno skladište.
   Microsoft SQL Server 2022 (T-SQL)
   ===================================================================== */
USE master;
GO
IF DB_ID(N'StanicaERP') IS NOT NULL
BEGIN
    ALTER DATABASE StanicaERP SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE StanicaERP;
END
GO
-- Croatian_CI_AS: ispravno sortiranje i poređenje za č, ć, đ, š, ž
CREATE DATABASE StanicaERP COLLATE Croatian_CI_AS;
GO
USE StanicaERP;
GO
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

/* ---------- Šifarnici ---------- */

-- Prodajni objekti: pumpe (maloprodaja) i veleprodajno skladište
CREATE TABLE dbo.Objekat (
    ObjekatId   INT IDENTITY(1,1) CONSTRAINT PK_Objekat PRIMARY KEY,
    Sifra       NVARCHAR(10)  NOT NULL CONSTRAINT UQ_Objekat_Sifra UNIQUE,
    Naziv       NVARCHAR(100) NOT NULL,
    Tip         CHAR(2)       NOT NULL CONSTRAINT CK_Objekat_Tip CHECK (Tip IN ('MP', 'VP')),
    Grad        NVARCHAR(50)  NOT NULL
);

CREATE TABLE dbo.Grupa (
    GrupaId     INT IDENTITY(1,1) CONSTRAINT PK_Grupa PRIMARY KEY,
    Naziv       NVARCHAR(50) NOT NULL CONSTRAINT UQ_Grupa_Naziv UNIQUE
);

CREATE TABLE dbo.Artikal (
    ArtikalId   INT IDENTITY(1,1) CONSTRAINT PK_Artikal PRIMARY KEY,
    Sifra       NVARCHAR(20)  NOT NULL CONSTRAINT UQ_Artikal_Sifra UNIQUE,
    Naziv       NVARCHAR(100) NOT NULL,
    GrupaId     INT           NOT NULL CONSTRAINT FK_Artikal_Grupa REFERENCES dbo.Grupa (GrupaId),
    JedMjere    NVARCHAR(5)   NOT NULL,
    PdvStopa    DECIMAL(5,2)  NOT NULL CONSTRAINT DF_Artikal_Pdv DEFAULT (17.00)
                              CONSTRAINT CK_Artikal_Pdv CHECK (PdvStopa BETWEEN 0 AND 100),
    MinZaliha   DECIMAL(18,3) NOT NULL CONSTRAINT DF_Artikal_Min DEFAULT (0),
    Aktivan     BIT           NOT NULL CONSTRAINT DF_Artikal_Aktivan DEFAULT (1),
    Kreirano    DATETIME2(0)  NOT NULL CONSTRAINT DF_Artikal_Kreirano DEFAULT (SYSDATETIME())
);

-- Kupci (K), dobavljači (D) ili oboje (O)
CREATE TABLE dbo.Partner (
    PartnerId   INT IDENTITY(1,1) CONSTRAINT PK_Partner PRIMARY KEY,
    Naziv       NVARCHAR(100) NOT NULL,
    JIB         CHAR(13)      NULL,
    Tip         CHAR(1)       NOT NULL CONSTRAINT CK_Partner_Tip CHECK (Tip IN ('K', 'D', 'O')),
    Grad        NVARCHAR(50)  NULL,
    RabatPosto  DECIMAL(5,2)  NOT NULL CONSTRAINT DF_Partner_Rabat DEFAULT (0)
                              CONSTRAINT CK_Partner_Rabat CHECK (RabatPosto BETWEEN 0 AND 50)
);
-- JIB jedinstven, ali samo kad je unesen (filtrirani indeks dozvoljava više NULL-ova)
CREATE UNIQUE INDEX UX_Partner_JIB ON dbo.Partner (JIB) WHERE JIB IS NOT NULL;

/* ---------- Cijene i zalihe ---------- */

-- Važeća maloprodajna cijena (MPC, sa PDV-om) po objektu
CREATE TABLE dbo.Cijena (
    ObjekatId   INT           NOT NULL CONSTRAINT FK_Cijena_Objekat REFERENCES dbo.Objekat (ObjekatId),
    ArtikalId   INT           NOT NULL CONSTRAINT FK_Cijena_Artikal REFERENCES dbo.Artikal (ArtikalId),
    MPC         DECIMAL(18,4) NOT NULL CONSTRAINT CK_Cijena_MPC CHECK (MPC > 0),
    VaziOd      DATETIME2(0)  NOT NULL CONSTRAINT DF_Cijena_VaziOd DEFAULT (SYSDATETIME()),
    CONSTRAINT PK_Cijena PRIMARY KEY (ObjekatId, ArtikalId)
);

-- Puni je trigger trg_Cijena_Historija (revizijski trag svake promjene cijene)
CREATE TABLE dbo.CijenaHistorija (
    HistorijaId INT IDENTITY(1,1) CONSTRAINT PK_CijenaHistorija PRIMARY KEY,
    ObjekatId   INT           NOT NULL,
    ArtikalId   INT           NOT NULL,
    StaraMPC    DECIMAL(18,4) NULL,
    NovaMPC     DECIMAL(18,4) NOT NULL,
    Promijenjeno DATETIME2(0) NOT NULL CONSTRAINT DF_CijenaHistorija_Datum DEFAULT (SYSDATETIME()),
    Korisnik    NVARCHAR(128) NOT NULL CONSTRAINT DF_CijenaHistorija_Korisnik DEFAULT (SUSER_SNAME())
);

-- Trenutno stanje zaliha po objektu, sa prosječnom nabavnom cijenom
CREATE TABLE dbo.Lager (
    ObjekatId      INT           NOT NULL CONSTRAINT FK_Lager_Objekat REFERENCES dbo.Objekat (ObjekatId),
    ArtikalId      INT           NOT NULL CONSTRAINT FK_Lager_Artikal REFERENCES dbo.Artikal (ArtikalId),
    Kolicina       DECIMAL(18,3) NOT NULL CONSTRAINT DF_Lager_Kolicina DEFAULT (0),
    ProsjNabCijena DECIMAL(18,4) NOT NULL CONSTRAINT DF_Lager_Nab DEFAULT (0),
    CONSTRAINT PK_Lager PRIMARY KEY (ObjekatId, ArtikalId),
    -- Zadnja linija odbrane: zaliha nikad ne smije u minus
    CONSTRAINT CK_Lager_Kolicina CHECK (Kolicina >= 0)
);

/* ---------- Dokumenti ---------- */

-- Brojač dokumenata po vrsti i godini (KAL-2026-0001, ...)
CREATE TABLE dbo.Brojac (
    Vrsta   CHAR(3) NOT NULL,
    Godina  INT     NOT NULL,
    Zadnji  INT     NOT NULL,
    CONSTRAINT PK_Brojac PRIMARY KEY (Vrsta, Godina)
);

/*  Vrste dokumenata:
    KAL = kalkulacija (ulaz robe od dobavljača)
    RAC = maloprodajni račun (izlaz, cijene SA PDV-om)
    FAK = veleprodajna faktura (izlaz, cijene BEZ PDV-a, rabat)
    NIV = nivelacija (promjena maloprodajne cijene)
    Status: O = otvoren, P = proknjižen                                   */
CREATE TABLE dbo.Dokument (
    DokumentId  INT IDENTITY(1,1) CONSTRAINT PK_Dokument PRIMARY KEY,
    Vrsta       CHAR(3)       NOT NULL CONSTRAINT CK_Dokument_Vrsta CHECK (Vrsta IN ('KAL', 'RAC', 'FAK', 'NIV')),
    Broj        NVARCHAR(20)  NOT NULL,
    Datum       DATE          NOT NULL,
    ObjekatId   INT           NOT NULL CONSTRAINT FK_Dokument_Objekat REFERENCES dbo.Objekat (ObjekatId),
    PartnerId   INT           NULL     CONSTRAINT FK_Dokument_Partner REFERENCES dbo.Partner (PartnerId),
    Status      CHAR(1)       NOT NULL CONSTRAINT DF_Dokument_Status DEFAULT ('O')
                              CONSTRAINT CK_Dokument_Status CHECK (Status IN ('O', 'P')),
    Napomena    NVARCHAR(200) NULL,
    Kreirano    DATETIME2(0)  NOT NULL CONSTRAINT DF_Dokument_Kreirano DEFAULT (SYSDATETIME()),
    Proknjizeno DATETIME2(0)  NULL,
    CONSTRAINT UQ_Dokument_Broj UNIQUE (Vrsta, Broj),
    -- Ulaz i veleprodaja moraju imati partnera
    CONSTRAINT CK_Dokument_Partner CHECK (Vrsta IN ('RAC', 'NIV') OR PartnerId IS NOT NULL)
);

CREATE TABLE dbo.DokumentStavka (
    StavkaId    INT IDENTITY(1,1) CONSTRAINT PK_DokumentStavka PRIMARY KEY,
    DokumentId  INT           NOT NULL CONSTRAINT FK_Stavka_Dokument REFERENCES dbo.Dokument (DokumentId),
    ArtikalId   INT           NOT NULL CONSTRAINT FK_Stavka_Artikal REFERENCES dbo.Artikal (ArtikalId),
    Kolicina    DECIMAL(18,3) NOT NULL CONSTRAINT CK_Stavka_Kolicina CHECK (Kolicina >= 0),
    -- KAL: nabavna cijena bez PDV | RAC: MPC sa PDV | FAK: VPC bez PDV | NIV: nova MPC
    Cijena      DECIMAL(18,4) NOT NULL CONSTRAINT CK_Stavka_Cijena CHECK (Cijena >= 0),
    -- NIV: stara MPC (za obračun razlike)
    StaraCijena DECIMAL(18,4) NULL,
    -- Prosječna nabavna cijena u trenutku izlaza (puni se pri knjiženju) -> za RUC
    NabCijena   DECIMAL(18,4) NULL,
    RabatPosto  DECIMAL(5,2)  NOT NULL CONSTRAINT DF_Stavka_Rabat DEFAULT (0)
                              CONSTRAINT CK_Stavka_Rabat CHECK (RabatPosto BETWEEN 0 AND 100),
    PdvStopa    DECIMAL(5,2)  NOT NULL
);

-- Kartica artikla: svaki ulaz i izlaz robe
CREATE TABLE dbo.LagerPromet (
    PrometId    BIGINT IDENTITY(1,1) CONSTRAINT PK_LagerPromet PRIMARY KEY,
    ObjekatId   INT           NOT NULL CONSTRAINT FK_Promet_Objekat REFERENCES dbo.Objekat (ObjekatId),
    ArtikalId   INT           NOT NULL CONSTRAINT FK_Promet_Artikal REFERENCES dbo.Artikal (ArtikalId),
    DokumentId  INT           NOT NULL CONSTRAINT FK_Promet_Dokument REFERENCES dbo.Dokument (DokumentId),
    Datum       DATE          NOT NULL,
    Ulaz        DECIMAL(18,3) NOT NULL CONSTRAINT DF_Promet_Ulaz DEFAULT (0),
    Izlaz       DECIMAL(18,3) NOT NULL CONSTRAINT DF_Promet_Izlaz DEFAULT (0),
    NabCijena   DECIMAL(18,4) NOT NULL
);

/* ---------- Indeksi za najčešće upite ---------- */
CREATE INDEX IX_Dokument_Datum        ON dbo.Dokument (Datum, Vrsta) INCLUDE (ObjekatId, Status, PartnerId);
CREATE INDEX IX_DokumentStavka_Dok    ON dbo.DokumentStavka (DokumentId);
CREATE INDEX IX_DokumentStavka_Art    ON dbo.DokumentStavka (ArtikalId);
CREATE INDEX IX_LagerPromet_Kartica   ON dbo.LagerPromet (ObjekatId, ArtikalId, Datum) INCLUDE (Ulaz, Izlaz, NabCijena);
CREATE INDEX IX_Artikal_Naziv         ON dbo.Artikal (Naziv);
GO
