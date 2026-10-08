
drop user if exists 'bpc'@'localhost';
create user 'bpc'@'localhost' identified by '1234';
drop database if exists bpc;
create database bpc;

grant all privileges on bpc.* to 'bpc'@'localhost';
flush privileges;

use bpc;

create table ProductCategory (
    CategoryID int auto_increment primary key,
    CategoryName varchar(80) not null unique,
    Description varchar(255)
);
create table Product (
    ProductID int auto_increment primary key,
    ProductName varchar(120) not null unique,
    CategoryID int not null,
    DosageForm varchar(50) not null,
    UnitPrice decimal(10,2) not null check (UnitPrice > 0),
    ReorderLevel int not null default 0,
    ShelfLifeMonths int not null default 24 check (ShelfLifeMonths > 0),
    Picture varchar(120),
    Description varchar(1000),
    foreign key (CategoryID) references ProductCategory(CategoryID)
        on update cascade
        on delete no action
);
create table Supplier (
    SupplierID int auto_increment primary key,
    SupName varchar(120) not null,
    Country varchar(60)  not null,
    Phone varchar(30),
    Email varchar(120),
    Rating decimal(3,2) check (Rating between 0 and 5)
);
create table RawMaterial (
    MaterialID int auto_increment primary key,
    MaterialName varchar(120) not null,
    Category varchar(60) not null,
    Unit varchar(20)  not null,
    ReorderLevel int not null default 0
);
create table ProductFormula (
    ProductID int not null,
    MaterialID int not null,
    QuantityPer100 decimal(10,2) not null check (QuantityPer100 > 0),
    primary key (ProductID, MaterialID),
    foreign key (ProductID) references Product(ProductID)
        on update cascade
        on delete cascade,
    foreign key (MaterialID) references RawMaterial(MaterialID)
        on update cascade
        on delete no action
);
create table SupplierMaterial (
    SupplierID int not null,
    MaterialID int not null,
    primary key (SupplierID, MaterialID),
    foreign key (SupplierID) references Supplier(SupplierID)
        on update cascade
        on delete cascade,
    foreign key (MaterialID) references RawMaterial(MaterialID)
        on update cascade
        on delete cascade
);
create table Warehouse (
    WarehouseID int auto_increment primary key,
    WarehouseName varchar(120) not null,
    Location varchar(120) not null,
    Capacity int not null,
	Type varchar(20) not null check (Type in ('RawMaterials','FinishedGoods','Distribution'))
);
create table Employee (
    EmpID int auto_increment primary key,
    Username varchar(50) not null unique,
    Password varchar(60) not null default 'emp',
    EmpName varchar(120) not null,
    Role varchar(60) not null,
    Phone varchar(30),
    Email varchar(120),
    WarehouseID int,
    Salary decimal(10,2) not null,
    HireDate date not null,
    foreign key (WarehouseID) references Warehouse(WarehouseID)
        on update cascade
        on delete no action
);
create table PurchaseOrder (
    POID int auto_increment primary key,
    SupplierID int not null,
    OrderDate date not null,
    ExpectedDeliveryDate date not null,
    Status varchar(15) not null check (status in ('Pending','Received','Cancelled')),
    TotalCost decimal(12,2) not null default 0,
    check (ExpectedDeliveryDate >= OrderDate),
    check (TotalCost >= 0),
    foreign key (SupplierID) references Supplier(SupplierID)
        on update cascade
        on delete no action
);

create table PurchaseOrderItem (
    POItemID int auto_increment primary key,
    POID int not null,
    MaterialID int not null,
    Quantity int not null check (Quantity > 0),
    UnitCost decimal(10,2) not null check (UnitCost >= 0),
    foreign key (POID) references PurchaseOrder(POID)
        on update cascade
        on delete cascade,
    foreign key (MaterialID) references RawMaterial(MaterialID)
        on update cascade
        on delete no action
);
create table GoodsReceipt (
    ReceiptID int auto_increment primary key,
    POID int not null,
    EmpID int not null,
    ReceivedDate date not null,
    Notes varchar(255),
    foreign key (POID) references PurchaseOrder(POID)
        on update cascade
        on delete no action,
    foreign key (EmpID) references Employee(EmpID)
        on update cascade
        on delete no action
);

create table GoodsReceiptItem (
    ReceiptItemID int auto_increment primary key,
    ReceiptID int not null,
    POItemID int not null,
    QuantityReceived int not null check (QuantityReceived >= 0),
    Notes varchar(255),
    foreign key (ReceiptID) references GoodsReceipt(ReceiptID)
        on update cascade
        on delete cascade,
    foreign key (POItemID) references PurchaseOrderItem(POItemID)
        on update cascade
        on delete no action
);
create table RawMaterialBatch (
    RMBatchID int auto_increment primary key,
    MaterialID int not null,
    WarehouseID int not null,
    ReceiptItemID int,
    Quantity int not null check (Quantity >= 0),
    ReceivedDate date not null,
    ExpiryDate date not null,
    check (ExpiryDate > ReceivedDate),
    foreign key (MaterialID) references RawMaterial(MaterialID)
        on update cascade
        on delete no action,
    foreign key (WarehouseID) references Warehouse(WarehouseID)
        on update cascade
        on delete no action,
    foreign key (ReceiptItemID) references GoodsReceiptItem(ReceiptItemID)
        on update cascade
        on delete no action
);
create table ProductBatch (
    ProductBatchID int auto_increment primary key,
    ProductID int not null,
    WarehouseID int not null,
    Quantity int not null check (Quantity >= 0),
    ManufactureDate date not null,
    ExpiryDate date not null,
    check (ExpiryDate > ManufactureDate),
    foreign key (ProductID) references Product(ProductID)
        on update cascade
        on delete no action,
    foreign key (WarehouseID) references Warehouse(WarehouseID)
        on update cascade
        on delete no action
);

create table ProductionOrder (
    ProductionID int auto_increment primary key,
    ProductBatchID int  not null unique,
    ProductionDate date not null,
    QuantityProduced int  not null check (QuantityProduced > 0),
    EmpID int  not null,
    Status varchar(60) not null check (Status in('Planned','InProgress','Completed','Cancelled')),
    foreign key (ProductBatchID) references ProductBatch(ProductBatchID)
        on update cascade
        on delete no action,
    foreign key (EmpID) references Employee(EmpID)
        on update cascade
        on delete no action
);
create table ProductionMaterial (
    ProductionID int not null,
    RMBatchID int not null,
    QuantityUsed int not null check (QuantityUsed > 0),
    primary key (ProductionID, RMBatchID),
    foreign key (ProductionID) references ProductionOrder(ProductionID)
        on update cascade
        on delete cascade,
    foreign key (RMBatchID) references RawMaterialBatch(RMBatchID)
        on update cascade
        on delete no action
);
create table StockTransfer (
    TransferID int auto_increment primary key,
    FromWarehouseID int not null,
    ToWarehouseID int not null,
    ProductBatchID int not null,
    Quantity int not null check (Quantity > 0),
    TransferDate date not null,
    EmpID int  not null,
    foreign key (FromWarehouseID) references Warehouse(WarehouseID)
        on update cascade
        on delete no action,
    foreign key (ToWarehouseID) references Warehouse(WarehouseID)
        on update cascade
        on delete no action,
    foreign key (ProductBatchID) references ProductBatch(ProductBatchID)
        on update cascade
        on delete no action,
    foreign key (EmpID) references Employee(EmpID)
        on update cascade
        on delete no action
);
create table SupplierInvoice (
    SuppInvoiceID int auto_increment primary key,
    POID int not null,
    IssueDate date not null,
    DueDate date not null,
    TotalAmount decimal(12,2) not null,
    Status varchar(60) not null check (Status in('Open','PartiallyPaid','Paid','Overdue')),
    foreign key (POID) references PurchaseOrder(POID)
        on update cascade
        on delete no action
);
create table SupplierPayment (
    SupPayID int auto_increment primary key,
    SuppInvoiceID int not null,
    Amount decimal(12,2) not null check (Amount > 0),
    PaymentDate date not null,
    Method varchar(60) not null check (Method in('BankTransfer','Cheque','Cash','LetterOfCredit')),
    foreign key (SuppInvoiceID) references SupplierInvoice(SuppInvoiceID)
        on update cascade
        on delete no action
);
create table Customer (
    CustomerID int auto_increment primary key,
    Username varchar(50)  not null unique,
    CustomerName varchar(150) not null,
    Type varchar(20) not null check (Type in('Hospital','Clinic','Pharmacy','Distributor','Export')),
    City varchar(80)  not null,
    Phone varchar(30),
    Email varchar(120),
    PaymentTerms varchar(60),
    Password varchar(60)  not null default 'customer'
);
create table SalesOrder (
    OrderID int auto_increment primary key,
    CustomerID int not null,
    OrderDate date not null,
    DeliveryDate date,
    Status varchar(20) not null check (Status in('Pending','Delivered','Cancelled')),
    TotalAmount decimal(12,2) not null default 0,
    Discount decimal(10,2) not null default 0,
    check (DeliveryDate is null or DeliveryDate >= OrderDate),
    check (TotalAmount >= 0),
    check (Discount >= 0),
    foreign key (CustomerID) references Customer(CustomerID)
        on update cascade
        on delete no action
);

create table SalesOrderItem (
    SOItemID int auto_increment primary key,
    OrderID int not null,
    ProductID int not null,
    Quantity int not null check (Quantity > 0),
    UnitPrice decimal(10,2) not null check (UnitPrice >= 0),
    foreign key (OrderID) references SalesOrder(OrderID)
        on update cascade
        on delete cascade,
    foreign key (ProductID) references Product(ProductID)
        on update cascade
        on delete no action
);

create table SalesOrderItemBatch (
    SOItemID int not null,
    ProductBatchID int not null,
    Quantity int not null check (Quantity > 0),
    primary key (SOItemID, ProductBatchID),
    foreign key (SOItemID) references SalesOrderItem(SOItemID)
        on update cascade
        on delete cascade,
    foreign key (ProductBatchID) references ProductBatch(ProductBatchID)
        on update cascade
        on delete no action
);
create table Invoice (
    InvoiceID int auto_increment primary key,
    OrderID int not null unique,
    IssueDate date not null,
    DueDate date not null,
    TotalAmount decimal(12,2) not null,
    Status varchar(30) not null check (Status in('Open','PartiallyPaid','Paid','Overdue')),
    check (DueDate >= IssueDate),
    check (TotalAmount >= 0),
    foreign key (OrderID) references SalesOrder(OrderID)
        on update cascade
        on delete no action
);
create table CustomerPayment (
    CustPayID int auto_increment primary key,
    InvoiceID int not null,
    Amount decimal(12,2) not null check (Amount > 0),
    PaymentDate date not null,
    Method varchar(30) not null check (Method in('BankTransfer','Cheque','Cash','CreditCard')),
    foreign key (InvoiceID) references Invoice(InvoiceID)
        on update cascade
        on delete no action
);

-- dummy data
INSERT INTO ProductCategory (CategoryName, Description) VALUES
('Antibiotics', 'Antibacterial pharmaceutical products'),
('Analgesics', 'Pain-relief and anti-inflammatory products'),
('Antidiabetics', 'Blood glucose regulation products'),
('Gastrointestinal', 'GI tract and antacid products'),
('Antihypertensives', 'Blood pressure regulation products'),
('Antihistamines', 'Allergy and cold relief products');
 
INSERT INTO Product (ProductName, CategoryID, DosageForm, UnitPrice, ReorderLevel, ShelfLifeMonths, Picture, Description) VALUES
('Abecedin',  6, 'Capsule',    12.50, 500,  24, 'abecedin.png',  'Multivitamin with B-complex and iron. Used for increased nutritional requirements due to acute or chronic disease, convalescence, post-surgical recovery, antibiotic therapy, and prevention of anaemia from iron or folic acid deficiency.'),
('Candistan', 1, 'Suspension', 18.75, 300,  18, 'candistan.png', 'Nystatin oral suspension. Antifungal active against a wide range of yeasts and yeast-like fungi (notably Candida). Acts by binding to ergosterol in the fungal cell membrane, increasing membrane permeability. Minimally absorbed from the gastrointestinal tract.'),
('Decomb',    6, 'Cream',      6.20,  800,  24, 'decomb.png',    'Topical corticosteroid + antifungal combination cream. Indicated for corticosteroid-responsive dermatoses complicated by secondary fungal or bacterial infection: atopic dermatitis, seborrheic dermatitis, lichen simplex chronicus, psoriasis, allergic contact dermatitis. Suitable for moist intertriginous areas.'),
('Dilax',     4, 'Tablet',     3.40,  1000, 36, 'dilax.png',     'Stimulant laxative for the treatment of occasional constipation. Also used as a bowel-cleansing regimen prior to surgery, x-ray of the colon, or endoscopic examination.'),
('Fergole',   6, 'Capsule',    9.80,  400,  24, 'fergole.png',   'Iron + folic acid supplement. Prevention of anaemia caused by iron and folic acid deficiency, especially the form associated with pregnancy.'),
('Fungitrin', 1, 'Cream',      11.20, 350,  24, 'fungitrin.png', 'Topical antifungal cream. Used for fungal infections of the skin, nails, and hair caused by dermatophytes or Candida species.'),
('Gingival',  4, 'Gel',        7.50,  600,  24, 'gingival.png',  'Oral gel for the treatment of gingivitis. Reduces redness, swelling, and bleeding of the gingivae on probing.'),
('Lamirase',  1, 'Solution',   5.90,  1000, 24, 'lamirase.png',  'Terbinafine 1% topical solution. Indicated for the topical treatment of tinea (pityriasis) versicolor caused by Malassezia furfur.'),
('Oracal',    4, 'Tablet',     14.30, 1500, 36, 'oracal.png',    'Antacid and calcium supplement. Used to neutralise stomach acid and provide elemental calcium for bone health.'),
('OrliSlim',  4, 'Capsule',    10.10, 200,  24, 'orliSlim.png',  'Orlistat capsules for weight management and obesity treatment. Reduces dietary fat absorption to create a calorie deficit; prescribed alongside a reduced-calorie diet for overweight or obese patients, especially those with weight-related health risks.'),
('Sedaprin',  2, 'Tablet',     4.80,  300,  36, 'sedaprin.png',  'Analgesic for mild-to-moderate pain: headache, migraine, neuralgia, toothache, sore throat, period pain, sprains and strains, rheumatic pain, sciatica, lumbago, fibrositis, muscular and joint pain. Also for feverishness and feverish colds.');
 
INSERT INTO Supplier (SupName, Country, Phone, Email, Rating) VALUES
('BASF Pharma Solutions', 'Germany', '+49 621 600', 'sales@basf-pharma.de', 4.80),
('Sun Pharma APIs', 'India', '+91 22 4324', 'export@sunpharma.in', 4.60),
('Hikma Pharmaceuticals', 'Jordan', '+962 6 580', 'orders@hikma.jo', 4.70),
('Pfizer Ingredients', 'United States', '+1 212 733', 'b2b@pfizer.com', 4.90),
('Lupin Limited', 'India', '+91 22 6640', 'sales@lupin.in', 4.50),
('Merck KGaA', 'Germany', '+49 6151 720', 'apis@merckgroup.de', 4.85),
('Tabuk Pharmaceuticals', 'Saudi Arabia', '+966 11 217', 'exports@tabukpharma.sa', 4.30);
 
INSERT INTO RawMaterial (MaterialName, Category, Unit, ReorderLevel) VALUES
('Amoxicillin API', 'API', 'kg', 50),
('Cefixime API', 'API', 'kg', 30),
('Ibuprofen API', 'API', 'kg', 80),
('Paracetamol API', 'API', 'kg', 100),
('Metformin HCl API', 'API', 'kg', 60),
('Omeprazole API', 'API', 'kg', 40),
('Amlodipine API', 'API', 'kg', 25),
('Hydrochlorothiazide API', 'API', 'kg', 20),
('Cetirizine API', 'API', 'kg', 15),
('Lactose Monohydrate', 'Excipient', 'kg', 200),
('Microcrystalline Cellulose', 'Excipient', 'kg', 150),
('Magnesium Stearate', 'Excipient', 'kg', 50),
('HPMC', 'Excipient', 'kg', 40),
('Talc', 'Excipient', 'kg', 60),
('PVC Blister Film', 'Packaging', 'm2', 500),
('Aluminium Foil', 'Packaging', 'm2', 400),
('Carton Boxes (large)', 'Packaging', 'pcs',1000);

INSERT INTO ProductFormula (ProductID, MaterialID, QuantityPer100) VALUES
-- Abecedin (multivitamin capsule)
(1,  1, 20.00), (1, 10, 5.00),  (1, 12, 2.00),
-- Candistan (antifungal suspension)
(2,  3, 15.00), (2,  4, 8.00),
-- Decomb (topical cream)
(3,  5, 10.00), (3,  6, 12.00),
-- Dilax (laxative tablet)
(4,  7, 6.00),  (4,  8, 4.00),  (4,  9, 2.00),
-- Fergole (iron + folic acid capsule)
(5,  3, 8.00),  (5, 11, 3.00),
-- Fungitrin (antifungal cream)
(6,  1, 10.00), (6,  2, 5.00),
-- Gingival (oral gel)
(7, 10, 4.00),  (7, 13, 6.00),
-- Lamirase (terbinafine solution)
(8,  4, 7.00),  (8, 11, 2.00),
-- Oracal (antacid tablet)
(9,  1, 18.00), (9,  3, 6.00),  (9,  7, 4.00),
-- OrliSlim (orlistat capsule)
(10, 2, 12.00), (10, 6, 3.00),
-- Sedaprin (analgesic tablet)
(11, 3, 5.00),  (11, 7, 8.00),  (11, 9, 2.00);

INSERT INTO SupplierMaterial (SupplierID, MaterialID) VALUES
(1, 1),(1, 2),(1, 10),(1, 12),(1, 13),
(2, 3),(2, 4),(2, 11),(2, 14),
(3, 5),(3, 6),
(4, 7),(4, 8),(4, 9),
(5, 3),(5, 4),(5, 11),
(6, 1),(6, 2),(6, 14),
(7, 15),(7, 16),(7, 17);
 
INSERT INTO Warehouse (WarehouseName, Location, Capacity, Type) VALUES
('Al-Bireh Raw Materials WH', 'Al-Bireh Industrial Zone', 50000, 'RawMaterials'),
('Al-Bireh Finished Goods WH', 'Al-Bireh Industrial Zone', 40000, 'FinishedGoods'),
('Ramallah Distribution Center', 'Ramallah', 30000, 'Distribution');
 
INSERT INTO Employee (Username, Password, EmpName, Role, Phone, Email, WarehouseID, Salary, HireDate) VALUES
('khaled', 'emp1', 'Khaled Mansour', 'Warehouse Manager', '+970 592 314871', 'k.mansour@bpc.ps', 1, 4500.00, '2018-03-15'),
('lina', 'emp2', 'Lina Hammad', 'Warehouse Manager', '+970 596 428903', 'l.hammad@bpc.ps', 2, 4500.00, '2019-07-01'),
('omar', 'emp3', 'Omar Saleh', 'Warehouse Manager', '+970 598 763214', 'o.saleh@bpc.ps', 3, 4700.00, '2017-09-10'),
('rania', 'emp4', 'Rania Abu-Awad', 'Procurement Officer', '+970 592 581047', 'r.abuawad@bpc.ps', 1, 3800.00, '2020-01-20'),
('tareq', 'emp5', 'Tareq Nasser', 'Procurement Officer', '+970 596 239765', 't.nasser@bpc.ps', 1, 3700.00, '2021-05-12'),
('sara', 'emp6', 'Sara Odeh', 'Sales Representative', '+970 598 412638', 's.odeh@bpc.ps', 3, 3500.00, '2019-11-04'),
('yousef', 'emp7', 'Yousef Khalil', 'Sales Representative', '+970 592 874156', 'y.khalil@bpc.ps', 3, 3500.00, '2022-02-18'),
('maha', 'emp8', 'Maha Ibrahim', 'Sales Representative', '+970 596 053729', 'm.ibrahim@bpc.ps', 3, 3600.00, '2020-08-25'),
('nidal', 'emp9', 'Nidal Hamdan', 'Production Officer', '+970 598 691423', 'n.hamdan@bpc.ps', 2, 2400.00, '2023-03-01'),
('dina', 'emp10', 'Dina Saadeh', 'Production Officer', '+970 592 147856', 'd.saadeh@bpc.ps', 1, 2400.00, '2022-10-15'),
('admin', 'admin', 'System Administrator','General Manager', '+970 599 000000', 'admin@bpc.ps', NULL, 8000.00, '2018-01-01');
 
INSERT INTO PurchaseOrder (SupplierID, OrderDate, ExpectedDeliveryDate, Status, TotalCost) VALUES
(1, '2025-06-10', '2025-07-15', 'Received', 18500.00),
(2, '2025-08-22', '2025-09-25', 'Received', 12000.00),
(3, '2025-10-05', '2025-11-10', 'Received', 9800.00),
(4, '2025-11-18', '2025-12-20', 'Received', 22300.00),
(5, '2026-01-08', '2026-02-12', 'Received', 7440.00),
(6, '2026-02-15', '2026-03-20', 'Received', 15500.00),
(1, '2026-03-01', '2026-04-05', 'Received', 11200.00),
(2, '2026-03-25', '2026-04-15', 'Pending', 14000.00),
(7, '2026-04-02', '2026-04-30', 'Pending', 6500.00),
(3, '2026-04-20', '2026-05-25', 'Pending', 9100.00),
(5, '2026-05-05', '2026-06-10', 'Pending', 4800.00),
(4, '2026-05-15', '2026-06-20', 'Pending', 18700.00);
 
INSERT INTO PurchaseOrderItem (POID, MaterialID, Quantity, UnitCost) VALUES
(1, 1, 100, 120.00),(1, 10, 200, 25.00),(1, 12, 50, 30.00),
(2, 3, 150, 60.00),(2, 4, 200, 15.00),
(3, 5, 100, 70.00),(3, 6, 80, 35.00),
(4, 7, 40, 280.00),(4, 8, 30, 250.00),(4, 9, 20, 180.00),
(5, 3, 100, 60.00),(5, 11, 80, 18.00),
(6, 1, 60, 125.00),(6, 2, 40, 200.00),
(7, 10, 300, 24.00),(7, 13, 100, 40.00),
(8, 3, 200, 60.00),(8, 14, 80, 25.00),
(9, 15, 200, 20.00),(9, 17, 500, 5.00),
(10, 6, 100, 35.00),(10, 5, 80, 70.00),
(11, 4, 200, 15.00),(11,11, 100, 18.00),
(12, 7, 30, 290.00),(12, 8, 40, 250.00);
 
INSERT INTO GoodsReceipt (POID, EmpID, ReceivedDate, Notes) VALUES
(1, 1, '2025-07-14', 'All items received in good condition'),
(2, 1, '2025-09-24', 'Minor delay, OK'),
(3, 1, '2025-11-08', 'Verified'),
(4, 1, '2025-12-19', 'COA checked'),
(5, 1, '2026-02-11', 'All OK'),
(6, 1, '2026-03-19', 'OK'),
(7, 1, '2026-04-04', 'OK');
 
INSERT INTO GoodsReceiptItem (ReceiptID, POItemID, QuantityReceived, Notes) VALUES
(1, 1, 100, NULL),(1, 2, 200, NULL),(1, 3, 50, NULL),
(2, 4, 150, NULL),(2, 5, 200, NULL),
(3, 6, 100, NULL),(3, 7, 80, NULL),
(4, 8, 40, NULL),(4, 9, 30, NULL),(4,10, 20, NULL),
(5,11, 100, NULL),(5,12, 80, NULL),
(6,13, 60, NULL),(6,14, 40, NULL),
(7,15, 300, NULL),(7,16, 100, NULL);
 
INSERT INTO RawMaterialBatch (MaterialID, WarehouseID, ReceiptItemID, Quantity, ReceivedDate, ExpiryDate) VALUES
( 1, 1, 1, 100, '2025-07-14', '2027-07-14'),
(10, 1, 2, 200, '2025-07-14', '2028-07-14'),
(12, 1, 3, 50, '2025-07-14', '2028-07-14'),
( 3, 1, 4, 150, '2025-09-24', '2027-09-24'),
( 4, 1, 5, 200, '2025-09-24', '2027-09-24'),
( 5, 1, 6, 100, '2025-11-08', '2026-06-15'),
( 6, 1, 7, 80, '2025-11-08', '2027-11-08'),
( 7, 1, 8, 40, '2025-12-19', '2027-12-19'),
( 8, 1, 9, 30, '2025-12-19', '2027-12-19'),
( 9, 1, 10, 20, '2025-12-19', '2026-06-10'),
( 3, 1, 11, 100, '2026-02-11', '2028-02-11'),
(11, 1, 12, 80, '2026-02-11', '2028-02-11'),
( 1, 1, 13, 60, '2026-03-19', '2028-03-19'),
( 2, 1, 14, 40, '2026-03-19', '2028-03-19'),
(10, 1, 15, 300, '2026-04-04', '2029-04-04'),
(13, 1, 16, 100, '2026-04-04', '2028-04-04');
 
INSERT INTO ProductBatch (ProductID, WarehouseID, Quantity, ManufactureDate, ExpiryDate) VALUES
( 1, 2, 2000, '2025-08-01', '2027-08-01'),
( 1, 3, 800, '2025-08-01', '2027-08-01'),
( 2, 2, 1200, '2025-09-10', '2027-09-10'),
( 3, 2, 3500, '2025-10-05', '2028-10-05'),
( 3, 3, 1500, '2025-10-05', '2028-10-05'),
( 4, 2, 4000, '2025-11-12', '2028-11-12'),
( 4, 3, 2000, '2025-11-12', '2028-11-12'),
( 5, 2, 1800, '2026-01-20', '2028-01-20'),
( 6, 2, 1600, '2026-02-15', '2028-02-15'),
( 7, 2, 2500, '2026-03-01', '2028-03-01'),
( 7, 3, 1000, '2026-03-01', '2028-03-01'),
( 8, 2, 800, '2026-03-10', '2026-06-10'),
( 9, 2, 1200, '2026-04-05', '2028-04-05'),
(10, 2, 900, '2026-04-12', '2028-04-12'),
(11, 2, 1500, '2026-05-01', '2028-05-01'),
(11, 3, 600, '2026-05-01', '2028-05-01');
 
INSERT INTO ProductionOrder (ProductBatchID, ProductionDate, QuantityProduced, EmpID, Status) VALUES
( 1, '2025-08-01', 2000, 9, 'Completed'),
( 3, '2025-09-10', 1200, 9, 'Completed'),
( 4, '2025-10-05', 3500, 10, 'Completed'),
( 6, '2025-11-12', 4000, 10, 'Completed'),
( 8, '2026-01-20', 1800, 9, 'Completed'),
( 9, '2026-02-15', 1600, 9, 'Completed'),
(10, '2026-03-01', 2500, 10, 'Completed'),
(13, '2026-04-05', 1200, 9, 'Completed'),
(14, '2026-04-12', 900, 10, 'Completed'),
(15, '2026-05-01', 1500, 9, 'Completed');
 
INSERT INTO ProductionMaterial (ProductionID, RMBatchID, QuantityUsed) VALUES
( 1, 1, 50),( 1, 2, 100),( 1, 3, 20),
( 2, 14, 24),( 2, 2, 60),
( 3, 4, 70),( 3, 12, 80),
( 4, 5, 200),( 4, 12, 50),
( 5, 6, 90),( 5, 2, 50),
( 6, 6, 80),( 6, 15, 80),
( 7, 7, 50),( 7, 16, 30),
( 8, 8, 12),( 8, 12, 30),
( 9, 9, 9),( 9, 12, 25),
(10, 10, 15),(10, 15, 80);
 
INSERT INTO StockTransfer (FromWarehouseID, ToWarehouseID, ProductBatchID, Quantity, TransferDate, EmpID) VALUES
(2, 3, 1, 500, '2025-09-05', 9),
(2, 3, 3, 300, '2025-10-12', 9),
(2, 3, 4, 800, '2025-11-08', 9),
(2, 3, 6, 1000, '2025-12-15', 9),
(2, 3, 10, 600, '2026-03-25', 9),
(2, 3, 13, 200, '2026-04-15', 9),
(2, 3, 15, 300, '2026-05-10', 9);
 
INSERT INTO SupplierInvoice (POID, IssueDate, DueDate, TotalAmount, Status) VALUES
(1, '2025-07-15', '2025-08-30', 18500.00, 'Paid'),
(2, '2025-09-25', '2025-11-10', 12000.00, 'Paid'),
(3, '2025-11-10', '2025-12-25', 9800.00, 'Paid'),
(4, '2025-12-20', '2026-02-05', 22300.00, 'Paid'),
(5, '2026-02-12', '2026-03-30', 7440.00, 'Paid'),
(6, '2026-03-20', '2026-05-05', 15500.00, 'PartiallyPaid'),
(7, '2026-04-05', '2026-05-20', 11200.00, 'Open'),
(8, '2026-04-15', '2026-05-15', 14000.00, 'Overdue'),
(9, '2026-04-30', '2026-05-30', 6500.00, 'Overdue');
 
INSERT INTO SupplierPayment (SuppInvoiceID, Amount, PaymentDate, Method) VALUES
(1, 18500.00, '2025-08-20', 'BankTransfer'),
(2, 12000.00, '2025-11-05', 'LetterOfCredit'),
(3, 9800.00, '2025-12-18', 'BankTransfer'),
(4, 22300.00, '2026-01-30', 'BankTransfer'),
(5, 7440.00, '2026-03-25', 'BankTransfer'),
(6, 8000.00, '2026-04-15', 'BankTransfer');
 
INSERT INTO Customer (Username, CustomerName, Type, City, Phone, Email, PaymentTerms, Password) VALUES
('najah', 'Al-Najah University Hospital', 'Hospital', 'Nablus', '+970 596 234591', 'pharmacy@najah-hospital.ps','Net 30','customer1'),
('hebron', 'Hebron Government Hospital', 'Hospital', 'Hebron', '+970 592 221112', 'orders@hebronhospital.ps', 'Net 45','customer2'),
('ramallah', 'Ramallah Medical Complex', 'Hospital', 'Ramallah', '+970 598 295700', 'pharma@rmc.ps', 'Net 30','customer3'),
('beitjala', 'Beit Jala Pharmacy', 'Pharmacy', 'Beit Jala', '+970 596 274400', 'info@bj-pharmacy.ps', 'Net 15','customer4'),
('alquds', 'Al-Quds Pharmacy Chain', 'Pharmacy', 'Jerusalem', '+970 592 627001', 'orders@quds-pharm.ps', 'Net 15','customer5'),
('gaza', 'Gaza Medical Distributors', 'Distributor', 'Gaza City', '+970 598 282900', 'sales@gmd.ps', 'Net 60','customer6'),
('nsc', 'Nablus Specialist Clinic', 'Clinic', 'Nablus', '+970 596 233450', 'admin@nsc.ps', 'Net 30','customer7'),
('bpoly', 'Bethlehem Polyclinic', 'Clinic', 'Bethlehem', '+970 592 274880', 'reception@bpoly.ps', 'Net 30','customer8'),
('jenin', 'Jenin General Hospital', 'Hospital', 'Jenin', '+970 598 250120', 'pharm@jeningh.ps', 'Net 45','customer9'),
('tulkarem', 'Tulkarem Pharmacy', 'Pharmacy', 'Tulkarem', '+970 596 267809', 'orders@tulpharm.ps', 'Net 15','customer10'),
('jpd', 'Jordan Pharma Distributors', 'Export', 'Amman', '+962 6 555 1010', 'imports@jpd.jo', 'Net 60','customer11'),
('medeast', 'MedEast Distributors', 'Export', 'Dubai', '+971 4 295 0099', 'orders@medeast.ae', 'Net 60','customer12');
 
INSERT INTO SalesOrder (CustomerID, OrderDate, DeliveryDate, Status, TotalAmount, Discount) VALUES
( 1, '2025-06-05', '2025-06-09', 'Delivered', 9470.00, 250.00),
( 4, '2025-06-22', '2025-06-25', 'Delivered', 3440.00, 0.00),
( 2, '2025-07-10', '2025-07-15', 'Delivered', 11070.00, 800.00),
( 6, '2025-07-28', '2025-08-02', 'Delivered', 10775.00, 500.00),
( 3, '2025-08-12', '2025-08-15', 'Delivered', 8440.00, 100.00),
( 5, '2025-09-04', '2025-09-08', 'Delivered', 4180.00, 0.00),
( 1, '2025-09-25', '2025-09-29', 'Delivered', 10010.00, 350.00),
( 7, '2025-10-08', '2025-10-11', 'Delivered', 2520.00, 0.00),
(11, '2025-10-20', '2025-10-28', 'Delivered', 18450.00,1500.00),
( 2, '2025-11-03', '2025-11-08', 'Delivered', 10525.00, 200.00),
( 4, '2025-11-19', '2025-11-22', 'Delivered', 3160.00, 0.00),
( 9, '2025-12-02', '2025-12-06', 'Delivered', 7080.00, 100.00),
( 6, '2025-12-15', '2025-12-21', 'Delivered', 10180.00, 600.00),
(12, '2026-01-08', '2026-01-18', 'Delivered', 17850.00,1200.00),
( 1, '2026-01-22', '2026-01-26', 'Delivered', 8900.00, 200.00),
( 3, '2026-02-04', '2026-02-07', 'Delivered', 10130.00, 250.00),
( 5, '2026-02-18', '2026-02-22', 'Delivered', 4870.00, 0.00),
( 2, '2026-03-01', '2026-03-05', 'Delivered', 12095.00, 700.00),
( 8, '2026-03-14', '2026-03-18', 'Delivered', 3680.00, 0.00),
( 6, '2026-03-25', '2026-03-30', 'Delivered', 9660.00, 350.00),
( 4, '2026-04-05', '2026-04-08', 'Delivered', 2880.00, 0.00),
( 1, '2026-04-15', '2026-04-19', 'Delivered', 9365.00, 150.00),
(11, '2026-04-22', '2026-05-01', 'Delivered', 17850.00,1800.00),
( 9, '2026-05-02', '2026-05-06', 'Delivered', 6100.00, 100.00),
( 7, '2026-05-10', '2026-05-13', 'Pending', 3440.00, 0.00),
( 3, '2026-05-15', '2026-05-20', 'Pending', 11865.00, 450.00),
( 5, '2026-05-18', NULL, 'Pending', 4794.00, 0.00);
 
INSERT INTO SalesOrderItem (OrderID, ProductID, Quantity, UnitPrice) VALUES
( 1,  1,   500,   12.5),( 1,  4,   800,    3.4),( 1,  7,   100,    7.5),( 2,  3,   400,    6.2),
( 2, 11,   200,    4.8),( 3,  1,   600,   12.5),( 3,  5,   300,    9.8),( 3,  9,   100,   14.3),
( 4,  2,   300,  18.75),( 4,  4,  1000,    3.4),( 4,  7,   300,    7.5),( 5,  3,   500,    6.2),
( 5,  6,   400,   11.2),( 5, 11,   200,    4.8),( 6,  7,   400,    7.5),( 6,  8,   200,    5.9),
( 7,  1,   400,   12.5),( 7,  4,  1000,    3.4),( 7,  5,   200,    9.8),( 8,  4,   600,    3.4),
( 8, 11,   100,    4.8),( 9,  1,   800,   12.5),( 9,  3,  1000,    6.2),( 9,  7,   500,    7.5),
(10,  2,   300,  18.75),(10,  6,   200,   11.2),(10,  9,   200,   14.3),(11,  3,   400,    6.2),
(11,  4,   200,    3.4),(12,  1,   300,   12.5),(12,  5,   350,    9.8),(13,  4,  1500,    3.4),
(13,  7,   600,    7.5),(13,  8,   200,    5.9),(14,  1,   500,   12.5),(14,  4,  2000,    3.4),
(14,  7,   800,    7.5),(15,  2,   200,  18.75),(15,  5,   400,    9.8),(15,  9,   100,   14.3),
(16,  1,   400,   12.5),(16,  6,   300,   11.2),(16, 10,   200,   10.1),(17,  3,   500,    6.2),
(17,  8,   300,    5.9),(18,  1,   500,   12.5),(18,  5,   400,    9.8),(18,  7,   350,    7.5),
(19,  4,   800,    3.4),(19, 11,   200,    4.8),(20,  2,   300,  18.75),(20,  6,   200,   11.2),
(20,  9,   150,   14.3),(21,  3,   300,    6.2),(21,  4,   300,    3.4),(22,  1,   400,   12.5),
(22,  7,   400,    7.5),(22, 10,   150,   10.1),(23,  1,   700,   12.5),(23,  4,  2500,    3.4),
(23, 11,   500,    4.8),(24,  2,   200,  18.75),(24,  5,   250,    9.8),(25,  3,   400,    6.2),
(25, 11,   200,    4.8),(26,  1,   500,   12.5),(26,  5,   400,    9.8),(26,  9,   150,   14.3),
(27,  6,   270,   11.2),(27,  8,   300,    5.9);

INSERT INTO SalesOrderItemBatch (SOItemID, ProductBatchID, Quantity) VALUES
(  1,  1,   500),(  2,  6,   800),(  3, 10,   100),(  4,  4,   400),(  5, 15,   200),
(  6,  1,   600),(  7,  8,   300),(  8, 13,   100),(  9,  3,   300),( 10,  6,  1000),
( 11, 10,   300),( 12,  4,   500),( 13,  9,   400),( 14, 15,   200),( 15, 10,   400),
( 16, 12,   200),( 17,  1,   400),( 18,  6,  1000),( 19,  8,   200),( 20,  7,   600),
( 21, 16,   100),( 22,  2,   800),( 23,  5,  1000),( 24, 11,   500),( 25,  3,   300),
( 26,  9,   200),( 27, 13,   200),( 28,  4,   400),( 29,  6,   200),( 30,  1,   300),
( 31,  8,   350),( 32,  7,  1500),( 33, 11,   600),( 34, 12,   200),( 35,  2,   500),
( 36,  7,  2000),( 37, 11,   800),( 38,  3,   200),( 39,  8,   400),( 40, 13,   100),
( 41,  1,   400),( 42,  9,   300),( 43, 14,   200),( 44,  4,   500),( 45, 12,   300),
( 46,  1,   500),( 47,  8,   400),( 48, 10,   350),( 49,  6,   800),( 50, 15,   200),
( 51,  3,   300),( 52,  9,   200),( 53, 13,   150),( 54,  4,   300),( 55,  6,   300),
( 56,  1,   400),( 57, 10,   400),( 58, 14,   150),( 59,  2,   700),( 60,  7,  2500),
( 61, 16,   500),( 62,  3,   200),( 63,  8,   250),( 64,  4,   400),( 65, 15,   200),
( 66,  1,   500),( 67,  8,   400),( 68, 13,   150),( 69,  9,   270),( 70, 12,   300);

INSERT INTO Invoice (OrderID, IssueDate, DueDate, TotalAmount, Status) VALUES
( 1, '2025-06-09', '2025-07-09', 9470.00, 'Paid'),
( 2, '2025-07-21', '2025-08-20', 3440.00, 'Paid'),
( 3, '2025-09-08', '2025-10-08', 11070.00, 'Paid'),
( 4, '2025-10-13', '2025-11-12', 10775.00, 'Paid'),
( 5, '2025-11-26', '2025-12-26', 8440.00, 'Paid'),
( 6, '2025-12-19', '2026-01-18', 4180.00, 'Paid'),
( 7, '2026-01-12', '2026-02-11', 10010.00, 'Paid'),
( 8, '2026-02-04', '2026-03-06', 2520.00, 'Paid'),
( 9, '2026-02-23', '2026-03-25', 18450.00, 'Paid'),
(10, '2026-03-10', '2026-04-09', 10525.00, 'Paid'),
(11, '2026-03-19', '2026-04-18', 3160.00, 'Paid'),
(12, '2026-04-02', '2026-05-02', 7080.00, 'Open'),
(13, '2026-04-15', '2026-05-15', 10180.00, 'Open'),
(14, '2026-04-22', '2026-05-22', 17850.00, 'Open'),
(15, '2026-05-01', '2026-05-31', 8900.00, 'Open');

INSERT INTO CustomerPayment (InvoiceID, Amount, PaymentDate, Method) VALUES
( 1, 9470.00, '2025-07-01', 'BankTransfer'),
( 2, 3440.00, '2025-08-10', 'BankTransfer'),
( 3, 11070.00, '2025-10-02', 'BankTransfer'),
( 4, 10775.00, '2025-11-05', 'BankTransfer'),
( 5, 8440.00, '2025-12-20', 'BankTransfer'),
( 6, 4180.00, '2026-01-15', 'BankTransfer'),
( 7, 10010.00, '2026-02-05', 'BankTransfer'),
( 8, 2520.00, '2026-02-25', 'BankTransfer'),
( 9, 18450.00, '2026-03-20', 'BankTransfer'),
(10, 10525.00, '2026-04-05', 'BankTransfer'),
(11, 3160.00, '2026-04-15', 'BankTransfer');
