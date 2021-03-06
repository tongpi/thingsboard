/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;

import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;

import static org.thingsboard.common.util.DonAsynchron.withCallback;
import static org.thingsboard.rule.engine.api.TbRelationTypes.SUCCESS;

@Slf4j
@RuleNode(
        type = ComponentType.EXTERNAL,
        name = "发送邮件",
        configClazz = TbSendEmailNodeConfiguration.class,
        nodeDescription = "通过SMTP服务发送电子邮件。",
        nodeDetails = "期望为<b>SEND_EMAIL</b>类型的消息。该节点仅适用于使用<code>转换为邮件</code>转换节点创建的消息，" +
                "请使用<code>Successful</code>链将此节点连接到<code>转换为邮件</code>节点。",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbActionNodeSendEmailConfig",
        icon = "send"
)
public class TbSendEmailNode implements TbNode {

    private static final String MAIL_PROP = "mail.";
    static final String SEND_EMAIL_TYPE = "SEND_EMAIL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TbSendEmailNodeConfiguration config;
    private JavaMailSenderImpl mailSender;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        try {
            this.config = TbNodeUtils.convert(configuration, TbSendEmailNodeConfiguration.class);
            if (!this.config.isUseSystemSmtpSettings()) {
                mailSender = createMailSender();
            }
        } catch (Exception e) {
            throw new TbNodeException(e);
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            validateType(msg.getType());
            EmailPojo email = getEmail(msg);
            withCallback(ctx.getMailExecutor().executeAsync(() -> {
                        sendEmail(ctx, email);
                        return null;
                    }),
                    ok -> ctx.tellNext(msg, SUCCESS),
                    fail -> ctx.tellFailure(msg, fail));
        } catch (Exception ex) {
            ctx.tellFailure(msg, ex);
        }
    }

    private void sendEmail(TbContext ctx, EmailPojo email) throws Exception {
        if (this.config.isUseSystemSmtpSettings()) {
            ctx.getMailService().send(email.getFrom(), email.getTo(), email.getCc(),
                    email.getBcc(), email.getSubject(), email.getBody());
        } else {
            MimeMessage mailMsg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mailMsg, "UTF-8");
            helper.setFrom(email.getFrom());
            helper.setTo(email.getTo().split("\\s*,\\s*"));
            if (!StringUtils.isBlank(email.getCc())) {
                helper.setCc(email.getCc().split("\\s*,\\s*"));
            }
            if (!StringUtils.isBlank(email.getBcc())) {
                helper.setBcc(email.getBcc().split("\\s*,\\s*"));
            }
            helper.setSubject(email.getSubject());
            helper.setText(email.getBody());
            mailSender.send(helper.getMimeMessage());
        }
    }

    private EmailPojo getEmail(TbMsg msg) throws IOException {
        EmailPojo email = MAPPER.readValue(msg.getData(), EmailPojo.class);
        if (StringUtils.isBlank(email.getTo())) {
            throw new IllegalStateException("Email destination can not be blank [" + email.getTo() + "]");
        }
        return email;
    }

    private void validateType(String type) {
        if (!SEND_EMAIL_TYPE.equals(type)) {
            log.warn("Not expected msg type [{}] for SendEmail Node", type);
            throw new IllegalStateException("Not expected msg type " + type + " for SendEmail Node");
        }
    }

    @Override
    public void destroy() {
    }

    private JavaMailSenderImpl createMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(this.config.getSmtpHost());
        mailSender.setPort(this.config.getSmtpPort());
        mailSender.setUsername(this.config.getUsername());
        mailSender.setPassword(this.config.getPassword());
        mailSender.setJavaMailProperties(createJavaMailProperties());
        return mailSender;
    }

    private Properties createJavaMailProperties() {
        Properties javaMailProperties = new Properties();
        String protocol = this.config.getSmtpProtocol();
        javaMailProperties.put("mail.transport.protocol", protocol);
        javaMailProperties.put(MAIL_PROP + protocol + ".host", this.config.getSmtpHost());
        javaMailProperties.put(MAIL_PROP + protocol + ".port", this.config.getSmtpPort() + "");
        javaMailProperties.put(MAIL_PROP + protocol + ".timeout", this.config.getTimeout() + "");
        javaMailProperties.put(MAIL_PROP + protocol + ".auth", String.valueOf(StringUtils.isNotEmpty(this.config.getUsername())));
        javaMailProperties.put(MAIL_PROP + protocol + ".starttls.enable", Boolean.valueOf(this.config.isEnableTls()).toString());
        if (this.config.isEnableTls() && StringUtils.isNoneEmpty(this.config.getTlsVersion())) {
            javaMailProperties.put(MAIL_PROP + protocol + ".ssl.protocols", this.config.getTlsVersion());
        }
        return javaMailProperties;
    }
}
