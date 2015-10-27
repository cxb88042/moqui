/*
 * This software is in the public domain under CC0 1.0 Universal.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

/*
    JavaMail API Documentation at: https://java.net/projects/javamail/pages/Home
    For JavaMail JavaDocs see: https://javamail.java.net/nonav/docs/api/index.html
 */

import org.apache.commons.mail.HtmlEmail

import javax.mail.util.ByteArrayDataSource
import javax.activation.DataSource
import org.moqui.BaseException
import org.moqui.impl.context.ExecutionContextImpl

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.xml.transform.stream.StreamSource

Logger logger = LoggerFactory.getLogger("org.moqui.impl.sendEmailTemplate")

try {

    ExecutionContextImpl ec = context.ec

    // logger.info("sendEmailTemplate with emailTemplateId [${emailTemplateId}], bodyParameters [${bodyParameters}]")

    // add the bodyParameters to the context so they are available throughout this script
    if (bodyParameters) context.putAll(bodyParameters)

    def emailTemplate = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", emailTemplateId).one()
    if (!emailTemplate) ec.message.addError("No EmailTemplate record found for ID [${emailTemplateId}]")
    if (ec.message.hasError()) return

    // prepare the html message
    def bodyRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName((String) emailTemplate.webappName).renderMode("html")
    String bodyHtml = bodyRender.render()

    // prepare the alternative plain text message
    // render screen with renderMode=text for this
    def bodyTextRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName((String) emailTemplate.webappName).renderMode("text")
    String bodyText = bodyTextRender.render()

    def emailTemplateAttachmentList = emailTemplate."moqui.basic.email.EmailTemplateAttachment"
    def emailServer = emailTemplate."moqui.basic.email.EmailServer"

    // check a couple of required fields
    if (!emailServer) ec.message.addError("No EmailServer record found for EmailTemplate [${emailTemplateId}]")
    if (emailServer && !emailServer.smtpHost)
        ec.message.addError("SMTP Host is empty for EmailServer [${emailServer.emailServerId}]")
    if (emailTemplate && !emailTemplate.fromAddress)
        ec.message.addError("From address is empty for EmailTemplate [${emailTemplateId}]")
    if (ec.message.hasError()) {
        logger.info("Error sending email: ${ec.message.getErrorsString()}\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        return
    }

    String host = emailServer.smtpHost
    int port = (emailServer.smtpPort ?: "25") as int

    HtmlEmail email = new HtmlEmail()
    email.setHostName(host)
    email.setSmtpPort(port)
    if (emailServer.mailUsername) email.setAuthentication((String) emailServer.mailUsername, (String) emailServer.mailPassword)
    if (emailServer.smtpStartTls) {
        email.setStartTLSEnabled(emailServer.smtpStartTls == "Y")
        email.setStartTLSRequired(emailServer.smtpStartTls == "Y")
    }
    if (emailServer.smtpSsl) email.setSSLOnConnect(emailServer.smtpSsl == "Y")
    if (email.isSSLOnConnect()) {
        // email.setSslSmtpPort(port as String)
        // email.setSSLCheckServerIdentity(emailServer.smtpSsl == "Y")
    }

    // set the subject
    String subject = ec.resource.expand((String) emailTemplate.subject, "")
    email.setSubject(subject)

    // set from, reply to, bounce addresses
    email.setFrom((String) emailTemplate.fromAddress, (String) emailTemplate.fromName)
    if (emailTemplate.replyToAddresses) {
        def rtList = ((String) emailTemplate.replyToAddresses).split(",")
        for (def address in rtList) email.addReplyTo(address.trim())
    }
    if (emailTemplate.bounceAddress) email.setBounceAddress((String) emailTemplate.bounceAddress)

    // set to, cc, bcc addresses
    def toList = ((String) toAddresses).split(",")
    for (def toAddress in toList) email.addTo(toAddress.trim())
    if (emailTemplate.ccAddresses) {
        def ccList = ((String) emailTemplate.ccAddresses).split(",")
        for (def ccAddress in ccList) email.addCc(ccAddress.trim())
    }
    if (ccAddresses) {
        def ccList = ((String) ccAddresses).split(",")
        for (def ccAddress in ccList) email.addCc(ccAddress.trim())
    }
    if (emailTemplate.bccAddresses) {
        def bccList = ((String) emailTemplate.bccAddresses).split(",")
        for (def bccAddress in bccList) email.addBcc(bccAddress.trim())
    }
    if (bccAddresses) {
        def bccList = ((String) bccAddresses).split(",")
        for (def bccAddress in bccList) email.addBcc(bccAddress.trim())
    }

    // set the html message
    email.setHtmlMsg(bodyHtml)
    // set the alternative plain text message
    email.setTextMsg(bodyText)
    //email.setTextMsg("Your email client does not support HTML messages")

    for (def emailTemplateAttachment in emailTemplateAttachmentList) {
        if (emailTemplateAttachment.screenRenderMode) {
            def attachmentRender = ec.screen.makeRender().rootScreen((String) emailTemplateAttachment.attachmentLocation)
                    .webappName((String) emailTemplate.webappName).renderMode((String) emailTemplateAttachment.screenRenderMode)
            String attachmentText = attachmentRender.render()
            if (emailTemplateAttachment.screenRenderMode == "xsl-fo") {
                // use FOP to change to PDF, then attach that
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
                ec.resource.xslFoTransform(new StreamSource(new StringReader(attachmentText)), null, baos, "application/pdf")
                email.attach(new ByteArrayDataSource(baos.toByteArray(), "application/pdf"), (String) emailTemplateAttachment.fileName, "")
            } else {
                String mimeType = ec.screen.getMimeTypeByMode(emailTemplateAttachment.screenRenderMode)
                DataSource dataSource = new ByteArrayDataSource(attachmentText, mimeType)
                email.attach(dataSource, (String) emailTemplateAttachment.fileName, "")
            }
        } else {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource(emailTemplateAttachment.attachmentLocation)
            email.attach(dataSource, (String) emailTemplateAttachment.fileName, "")
        }
    }

    // create an moqui.basic.email.EmailMessage record with info about this sent message
    // NOTE: can do anything with: purposeEnumId, toUserId?
    if (createEmailMessage) {
        Map cemParms = [sentDate:ec.user.nowTimestamp, statusId:"ES_READY", subject:subject, body:bodyHtml,
                        fromAddress:emailTemplate.fromAddress, toAddresses:toAddresses,
                        ccAddresses:emailTemplate.ccAddresses, bccAddresses:emailTemplate.bccAddresses,
                        contentType:"text/html", emailTemplateId:emailTemplateId, fromUserId:ec.user?.userId]
        Map cemResults = ec.service.sync().name("create", "moqui.basic.email.EmailMessage").parameters(cemParms).disableAuthz().call()
        emailMessageId = cemResults.emailMessageId
    }

    logger.info("Sending email [${email.getSubject()}] from ${email.getFromAddress()} to ${email.getToAddresses()} cc ${email.getCcAddresses()} bcc ${email.getBccAddresses()} via ${emailServer.mailUsername}@${email.getHostName()}:${email.getSmtpPort()} SSL? ${email.isSSLOnConnect()}:${email.isSSLCheckServerIdentity()} TLS? ${email.isStartTLSEnabled()}:${email.isStartTLSRequired()} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
    email.setDebug(true)

    // send the email
    messageId = email.send()

    if (emailMessageId) {
        Map uemParms = [emailMessageId:emailMessageId, statusId:"ES_SENT", messageId:messageId]
        ec.service.sync().name("update", "moqui.basic.email.EmailMessage").parameters(uemParms).disableAuthz().call()
    }
} catch (Throwable t) {
    logger.info("Error in sendEmailTemplate groovy", t)
    throw new BaseException("Error in sendEmailTemplate", t)
}
