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
            log.error("Failed to send verification email to {}: {}", maskEmail(to), e.getMessage());
            throw new RuntimeException("Email delivery failed", e);
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
     * Send an invitation email to join a tenant.
     */
    public void sendInvitation(String to, String tenantName, String inviteLink) {
        String subject = "Приглашение в " + tenantName + " — Кадеро";
        String htmlBody = buildInvitationEmailBody(tenantName, inviteLink);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(emailConfig.getFrom(), emailConfig.getFromName(), "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("Invitation email sent to: {}", maskEmail(to));
        } catch (MessagingException | MailException | UnsupportedEncodingException e) {
            log.error("Failed to send invitation email to {}: {}", maskEmail(to), e.getMessage());
            throw new RuntimeException("Email delivery failed", e);
        }
    }

    private String buildInvitationEmailBody(String tenantName, String inviteLink) {
        return """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px;">
              <div style="text-align: center; margin-bottom: 24px;">
                <h1 style="font-size: 20px; color: #1a1a1a; margin: 0;">Кадеро</h1>
              </div>

              <p style="color: #333; font-size: 16px; line-height: 1.5;">
                Вас пригласили в организацию <strong>%s</strong>.
              </p>

              <div style="text-align: center; margin: 32px 0;">
                <a href="%s" style="display: inline-block; background: #dc2626; color: #fff; text-decoration: none; padding: 12px 32px; border-radius: 6px; font-size: 16px; font-weight: 600;">
                  Принять приглашение
                </a>
              </div>

              <p style="color: #666; font-size: 14px; line-height: 1.5;">
                Ссылка действительна 7 дней. Если вы не ожидали это приглашение, проигнорируйте это письмо.
              </p>

              <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;" />

              <p style="color: #999; font-size: 12px;">
                Это автоматическое сообщение от платформы Кадеро. Не отвечайте на него.
              </p>
            </div>
            """.formatted(tenantName, inviteLink);
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
