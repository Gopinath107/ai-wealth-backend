package com.djai.wealthadvisor.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResponseUtil {

    private static String cleanMsg(Exception e) {
        if (e == null || e.getMessage() == null) return "Unknown error";
        return e.getMessage().replaceAll("^[a-zA-Z.]+Exception: ", "");
    }

    public static Map<String, Object> buildResponse(Object data, boolean success, Exception e) {
        Map<String, Object> resp = new LinkedHashMap<>();
        
        if (success) {
            resp.put("result", data);
            resp.put("success", true);
            resp.put("errors", List.of());
            resp.put("errorCount", 0);
        } else {
            resp.put("result", null);
            resp.put("success", false);
            resp.put("errors", List.of(cleanMsg(e)));
            resp.put("errorCount", 1);
        }
        return resp;
    }

    public static Map<String, Object> buildError(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", null);
        resp.put("success", false);
        resp.put("errors", List.of(message));
        resp.put("errorCount", 1);
        return resp;
    }
}