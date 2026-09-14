package com.azki.reservation.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 为 JPA 审计字段提供当前操作人，优先使用 SecurityContext 中的登录身份。 */
@Component
public class AuditorAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            // 定时任务、初始化等没有登录上下文的操作统一记为 system。
            return Optional.of("system");
        }

        return Optional.of(authentication.getName());
    }
}
