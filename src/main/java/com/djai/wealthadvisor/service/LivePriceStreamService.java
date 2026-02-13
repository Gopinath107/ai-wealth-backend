package com.djai.wealthadvisor.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface LivePriceStreamService {
    SseEmitter streamLtp(String instrumentKey, long freqMs);
}
