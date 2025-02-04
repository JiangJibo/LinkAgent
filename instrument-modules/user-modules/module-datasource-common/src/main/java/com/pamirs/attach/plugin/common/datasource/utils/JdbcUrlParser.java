package com.pamirs.attach.plugin.common.datasource.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.Map;

public class JdbcUrlParser {

    private final static Logger logger = LoggerFactory.getLogger(JdbcUrlParser.class.getName());

    public static Map.Entry<String, String> extractUrl(String jdbc) {
        try {
            if (jdbc.contains("//")) {
                int i = jdbc.indexOf("//");
                String str = jdbc.substring(i + 2);
                String host = str.substring(0, str.indexOf(":"));
                String port = getPort(str, str.indexOf(":") + 1);
                return new AbstractMap.SimpleEntry<String, String>(host, port);
            }
            if (jdbc.contains("@")) {
                int i = jdbc.indexOf("@");
                String str = jdbc.substring(i + 1);
                String host = str.substring(0, str.indexOf(":"));
                String port = getPort(str, str.indexOf(":") + 1);
                return new AbstractMap.SimpleEntry<String, String>(host, port);
            }
        } catch (Exception e) {
            logger.error("parse jdbc url host/ip occur exception for url:{}", jdbc, e);
        }
        return new AbstractMap.SimpleEntry<String, String>("unknown", "unknown");
    }

    private static String getPort(String url, int start) {
        String port = "";
        char c = url.charAt(start);
        while (Character.isDigit(c)) {
            port = port + c;
            start++;
            c = url.charAt(start);
        }
        return port;
    }


}
