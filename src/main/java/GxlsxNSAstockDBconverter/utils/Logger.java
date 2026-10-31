package main.java.GxlsxNSAstockDBconverter.utils;

public class Logger {
    public static void LOG(String log) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[34;01mINFO\033[0m]\t" + structMsg + log;

        System.out.println(msg);
    }

    public static void WARN(String log) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[33;01mWARN\033[0m]\t" + structMsg + log;

        System.out.println(msg);
    }

    public static void ERROR(String error) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[31;01mERROR\033[0m]\t" + structMsg + error;

        System.err.println(msg);
    }

    public static void ERROR_printMsg(String error, String message) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[31;01mERROR\033[0m]\t" + structMsg + error;

        if (message != null)
            msg += "\nException's message:" +
                    message;

        System.err.println(msg);
    }

    public static void ERROR_printStacktrace(String error, StackTraceElement[] trace) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[31;01mERROR\033[0m]\t" + structMsg + error;

        if (trace != null) {
            msg += "\nstacktrace:";
            for (StackTraceElement t : trace)
                msg += "\n\t" + t;
        }
        System.err.println(msg);
    }

    public static void ERROR_printStacktrace_only(StackTraceElement[] trace) {
        String structMsg = Thread.currentThread().getStackTrace()[2].getFileName() +
                ":" + Thread.currentThread().getStackTrace()[2].getMethodName() +
                ":" + Thread.currentThread().getStackTrace()[2].getLineNumber() +
                "\t=>\t";
        String msg = "[\033[31;01mERROR\033[0m]\t" + structMsg + "stacktrace:";
        for (StackTraceElement t : trace)
            msg += "\n\t" + t;

        System.err.println(msg);
    }
}
