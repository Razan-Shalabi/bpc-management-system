# BPC – Sales, Purchasing & Warehouse Management System

COMP333 Database Systems – Final Project
Birzeit Pharmaceutical Company (BPC)

**Team:** Razan Shalabi (1230874) · Yasmine Abdel Haq (1230869)

A role-based JavaFX desktop application backed by MySQL that covers BPC's supply chain:
purchasing raw materials, warehouse & inventory management, production, and sales of
finished pharmaceutical products.

## Features

**Staff application** (each role only sees its own modules – see the table below)
- **Dashboard** – KPIs (orders, revenue, overdue invoices, open POs, low stock, expiring batches) and revenue charts
- **Sales** – sales orders with FEFO batch allocation (earliest expiry first), delivery, cancellation (stock returned), invoices and customer payments
- **Procurement** – suppliers and the materials they supply, purchase orders (create / receive / cancel), goods receipts, supplier invoices and payments
- **Warehouse** – warehouses, raw-material and product batches with expiry tracking, stock transfers (Finished Goods ↔ Distribution)
- **Catalog & Production** – categories, products (picture, description, formula), production orders that reserve raw-material batches (FEFO)
- **Reports** (managers) – 11 reports: customer activity, revenue by customer type, top products, overdue receivables, low-stock products, production output, supplier purchase ranking, multi-material suppliers, materials without supplier, low-stock raw materials, product stock by warehouse
- **Customer management** and **User management**

**Customer portal**
- Browse the product catalog with pictures, add to cart, place orders (pay now, partially, or later)
- My Orders (confirm delivery / cancel), My Invoices (pay), change password

### Business rules
- **Invoices** become *Overdue* automatically once the due date passes with money still owed;
  payments can't exceed the balance, be dated in the future, or before the invoice was issued.
- **Cancelling an order** returns its stock and removes its unpaid invoice; orders that already
  have a payment can't be cancelled.
- **Production**: planning an order reserves its raw materials; the finished goods are added to
  stock only when it is *Completed*; *Cancelling* returns the reserved materials.
- **Purchase orders** can be received or cancelled only while *Pending*, and invoiced once.
- Expired batches are never sold, transferred or used in production.
- Passwords are case-sensitive and stored as salted PBKDF2-SHA256 hashes (never as plain text);
  plain-text passwords from an older copy of the database are upgraded on first login.

### Roles
| Module | General Manager | Warehouse Manager | Sales Manager | Procurement Officer | Sales Representative | Production Officer |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| Dashboard, Reports | ✓ | ✓ | ✓ | | | |
| Sales | ✓ | | ✓ | | ✓ | |
| Procurement | ✓ | ✓ | | ✓ | | |
| Warehouse | ✓ | ✓ | | ✓ | | ✓ |
| Catalog & Production | ✓ | ✓ | Catalog only | | | ✓ |
| Customer Management | ✓ | | ✓ | | | |
| User Management | ✓ | | | | | |

## Requirements
- JDK 17 or newer (if `java -version` shows an older Java, set `JAVA_HOME` to your JDK 17 folder)
- [JavaFX SDK 17+](https://gluonhq.com/products/javafx/)
- MySQL 8
- [MySQL Connector/J](https://dev.mysql.com/downloads/connector/j/) – put the jar in `lib/`

## Setup
1. **Database** – run `database/bpc.sql` as the MySQL `root` user (e.g. in MySQL Workbench).
   It creates the `bpc` database, the `bpc`/`1234` user the app connects with, and demo data.
2. **Run**
   ```bat
   set PATH_TO_FX=C:\path\to\javafx-sdk-17\lib
   run.bat
   ```
   In Eclipse/IntelliJ: add the JavaFX and Connector/J jars, use VM options
   `--module-path "<javafx lib>" --add-modules javafx.controls`, and run `bpc.Main`.

## Demo accounts
| Role | Username | Password |
|---|---|---|
| General Manager (everything) | `admin` | `admin` |
| Warehouse Manager | `khaled` | `emp1` |
| Procurement Officer | `rania` | `emp4` |
| Sales Representative | `sara` | `emp6` |
| Production Officer | `nidal` | `emp9` |
| Customer (Al-Najah University Hospital) | `najah` | `customer1` |

## Tests
```bat
set PATH_TO_FX=C:\path\to\javafx-sdk-17\lib
set MYSQL_EXE=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe
test.bat
```
`test.bat` reloads the demo database before each suite (and at the end) and runs:
- `SmokeTest` – opens every staff and customer screen; fails on any SQL error or exception
- `FlowTest` – end-to-end flows: customer order with FEFO allocation, cancellation and stock
  return, paid orders can't be cancelled, PO receiving, duplicate invoices blocked
- `EdgeTest` – 126 edge cases that fill the real forms and check the message and the database:
  login, password hashing and role menus, invalid/too-long/too-large input, duplicates, deleting records still in
  use, dates in the future, discounts above the order value, overpayment (also inside the
  transaction), double receiving from two screens, production reserve/complete/cancel, expired
  stock, customer data isolation, password change, dashboard numbers

## Project structure
```
src/bpc/        Java source, logo and product pictures (src/bpc/drugs)
database/       bpc.sql – schema + demo data
test/bpc/       Automated tests
```
