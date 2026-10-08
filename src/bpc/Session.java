package bpc;

public final class Session {

    public enum Mode { STAFF, CUSTOMER }

    private static Mode    mode;
    private static String  username;
    // Staff only fields
    private static int     empId;
    private static String  empName;
    private static String  role;         
    private static boolean manager;
    // Customer only fields
    private static int     customerId;
    private static String  customerName;

    private Session() {}  


    public static void loginAsEmployee(int id, String user, String name, String roleStr) {
        mode = Mode.STAFF;
        username = user;
        empId = id;
        empName = name;
        role = roleStr;
        manager = roleStr != null && roleStr.toLowerCase().contains("manager");
        customerId = 0;
        customerName = null;
    }

    public static void loginAsCustomer(int id, String name) {
        mode = Mode.CUSTOMER;
        username = name;
        customerId = id;
        customerName = name;
        empId = 0;
        empName = null;
        role = null;
        manager = false;
    }

    public static void logout() {
        mode = null;
        username = null;
        empId = 0;
        empName = null;
        role = null;
        manager = false;
        customerId = 0;
        customerName = null;
    }

    public static Mode mode(){ return mode; }
    public static String username(){ return username; }
    public static int empId(){ return empId; }
    public static String empName(){ return empName; }
    public static String  role(){ return role; }
    public static boolean isManager(){ return manager; }

    public static boolean isGeneralManager() {
        return role != null && role.equalsIgnoreCase("General Manager");
    }

    public static int customerId() { return customerId; }
    public static String  customerName() { return customerName; }

    public static boolean isStaff() { return mode == Mode.STAFF; }
    public static boolean isCustomer() { return mode == Mode.CUSTOMER; }
}
