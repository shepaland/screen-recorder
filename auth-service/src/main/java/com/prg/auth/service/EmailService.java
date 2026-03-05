package com.prg.auth.service;

import com.prg.auth.config.EmailConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final EmailConfig emailConfig;

    /**
     * Send a verification OTP code to the specified email address.
     * The code is NOT included in the subject (security: subject is visible in notifications/previews).
     *
     * @param to   recipient email
     * @param code 6-digit OTP code
     */
    public void sendVerificationCode(String to, String code) {
        String subject = "Код подтверждения Кадеро";
        String htmlBody = buildVerificationEmailBody(code);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(emailConfig.getFrom(), emailConfig.getFromName(), "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("Verification email sent to: {}", maskEmail(to));
        } catch (MessagingException | MailException | UnsupportedEncodingException e) {
            // Fallback: log the code so testing can proceed without SMTP
            // In production, this should be removed or hidden behind a feature flag
            log.error("Failed to send verification email to {}: {}. FALLBACK: code={}", maskEmail(to), e.getMessage(), code);
            log.warn("SMTP unavailable — OTP code logged for testing. DO NOT use in production without SMTP!");
            // Do NOT throw — allow the flow to continue so the code is saved in DB
            // and can be verified using the code from logs
        }
    }

    private String buildVerificationEmailBody(String code) {
        return """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
              <div style="text-align: center; margin-bottom: 24px;">
                <h1 style="font-size: 20px; color: #1a1a1a; margin: 0;">Кадеро</h1>
              </div>
            
              <p style="color: #333; font-size: 16px; line-height: 1.5;">
                Ваш код подтверждения:
              </p>
            
              <div style="background: #f5f5f5; border-radius: 8px; padding: 20px; text-align: center; margin: 24px 0;">
                <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #1a1a1a;">
                  %s
                </span>
              </div>
            
              <p style="color: #666; font-size: 14px; line-height: 1.5;">
                Код действует 10 минут. Если вы не запрашивали код, проигнорируйте это письмо.
              </p>
            
              <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;" />
            
              <p style="color: #999; font-size: 12px;">
                Это автоматическое сообщение от платформы Кадеро. Не отвечайте на него.
              </p>
            </div>
            """.formatted(code);
    }

    /**
     * Mask email for log output: "user@domain.com" -> "us***@domain.com"
     */
    private String maskEmail(String email) {
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) return "***" + email.substring(atIdx);
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
