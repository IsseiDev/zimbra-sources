/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007, 2008, 2009, 2010, 2011 Zimbra, Inc.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.service.mail;

import com.zimbra.common.mailbox.Color;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.common.util.ArrayUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.mailbox.AutoSendDraftTask;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.MailServiceException.NoSuchItemException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mailbox.OperationContext;
import com.zimbra.cs.mailbox.util.TagUtil;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.cs.service.FileUploadServlet;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.cs.service.util.ItemIdFormatter;
import com.zimbra.cs.util.AccountUtil;
import com.zimbra.soap.ZimbraSoapContext;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

/**
 * @since Jun 11, 2005
 * @author dkarp
 */
public class SaveDraft extends MailDocumentHandler {

    private static final String[] TARGET_DRAFT_PATH = new String[] { MailConstants.E_MSG, MailConstants.A_ID };
    private static final String[] TARGET_FOLDER_PATH = new String[] { MailConstants.E_MSG, MailConstants.A_FOLDER };
    private static final String[] RESPONSE_ITEM_PATH = new String[] { };

    @Override
    protected String[] getProxiedIdPath(Element request) {
        return getXPath(request, TARGET_DRAFT_PATH) != null ? TARGET_DRAFT_PATH : TARGET_FOLDER_PATH;
    }

    @Override
    protected boolean checkMountpointProxy(Element request) {
        return getXPath(request, TARGET_DRAFT_PATH) == null;
    }

    @Override
    protected String[] getResponseItemPath()  { return RESPONSE_ITEM_PATH; }

    @Override
    public Element handle(Element request, Map<String, Object> context) throws ServiceException {
        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Mailbox mbox = getRequestedMailbox(zsc);
        OperationContext octxt = getOperationContext(zsc, context);
        ItemIdFormatter ifmt = new ItemIdFormatter(zsc);

        Element msgElem = request.getElement(MailConstants.E_MSG);

        int id = (int) msgElem.getAttributeLong(MailConstants.A_ID, Mailbox.ID_AUTO_INCREMENT);
        String originalId = msgElem.getAttribute(MailConstants.A_ORIG_ID, null);
        ItemId iidOrigid = originalId == null ? null : new ItemId(originalId, zsc);
        String replyType = msgElem.getAttribute(MailConstants.A_REPLY_TYPE, null);
        String identity = msgElem.getAttribute(MailConstants.A_IDENTITY_ID, null);
        String account = msgElem.getAttribute(MailConstants.A_FOR_ACCOUNT, null);

        // allow the caller to update the draft's metadata at the same time as they save the draft
        String folderId = msgElem.getAttribute(MailConstants.A_FOLDER, null);
        ItemId iidFolder = new ItemId(folderId == null ? "-1" : folderId, zsc);
        if (!iidFolder.belongsTo(mbox)) {
            throw ServiceException.INVALID_REQUEST("cannot move item between mailboxes", null);
        } else if (folderId != null && iidFolder.getId() <= 0) {
            throw MailServiceException.NO_SUCH_FOLDER(iidFolder.getId());
        }
        String flags = msgElem.getAttribute(MailConstants.A_FLAGS, null);
        String[] tags = TagUtil.parseTags(msgElem, mbox, octxt);
        Color color = ItemAction.getColor(msgElem);

        // check to see whether the entire message has been uploaded under separate cover
        String attachment = msgElem.getAttribute(MailConstants.A_ATTACHMENT_ID, null);
        long autoSendTime = new Long(msgElem.getAttribute(MailConstants.A_AUTO_SEND_TIME, "0"));

        ParseMimeMessage.MimeMessageData mimeData = new ParseMimeMessage.MimeMessageData();
        Message msg;
        try {
            MimeMessage mm;
            if (attachment != null) {
                mm = SendMsg.parseUploadedMessage(zsc, attachment, mimeData);
            } else {
                mm = ParseMimeMessage.parseMimeMsgSoap(zsc, octxt, mbox, msgElem, null, mimeData);
            }

            long date = System.currentTimeMillis();
            try {
                Date d = new Date();
                mm.setSentDate(d);
                date = d.getTime();
            } catch (Exception ignored) { }

            try {
                mm.saveChanges();
            } catch (MessagingException me) {
                throw ServiceException.FAILURE("completing MIME message object", me);
            }

            ParsedMessage pm = new ParsedMessage(mm, date, mbox.attachmentsIndexingEnabled());

            if (autoSendTime != 0) {
                Account acct = mbox.getAccount();
                long acctQuota = AccountUtil.getEffectiveQuota(acct);
                if (acct.isMailAllowReceiveButNotSendWhenOverQuota() && acctQuota != 0 && mbox.getSize() > acctQuota) {
                    throw MailServiceException.QUOTA_EXCEEDED(acctQuota);
                }
                Domain domain = Provisioning.getInstance().getDomain(acct);
                if (domain != null && !AccountUtil.isSendAllowedOverAggregateQuota(domain) &&
                        AccountUtil.isOverAggregateQuota(domain)) {
                    throw MailServiceException.DOMAIN_QUOTA_EXCEEDED(domain.getDomainAggregateQuota());
                }
            }

            String origid = iidOrigid == null ? null : iidOrigid.toString(account == null ? mbox.getAccountId() : account);

            msg = mbox.saveDraft(octxt, pm, id, origid, replyType, identity, account, autoSendTime);
        } catch (IOException e) {
            throw ServiceException.FAILURE("IOException while saving draft", e);
        } finally {
            // purge the messages fetched from other servers.
            if (mimeData.fetches != null) {
                FileUploadServlet.deleteUploads(mimeData.fetches);
            }
        }

        // we can now purge the uploaded attachments
        if (mimeData.uploads != null) {
            FileUploadServlet.deleteUploads(mimeData.uploads);
        }

        // try to set the metadata on the new/revised draft
        if (folderId != null || flags != null || !ArrayUtil.isEmpty(tags) || color != null) {
            try {
                // best not to fail if there's an error here...
                ItemActionHelper.UPDATE(octxt, mbox, zsc.getResponseProtocol(), Arrays.asList(msg.getId()),
                        MailItem.Type.MESSAGE, null, null, iidFolder, flags, tags, color);
                // and make sure the Message object reflects post-update reality
                msg = mbox.getMessageById(octxt, msg.getId());
            } catch (ServiceException e) {
                ZimbraLog.soap.warn("error setting metadata for draft " + msg.getId() + "; skipping that operation", e);
            }
        }

        if (schedulesAutoSendTask()) {
            if (id != Mailbox.ID_AUTO_INCREMENT) {
                // Cancel any existing auto-send task for this draft
                AutoSendDraftTask.cancelTask(id, mbox.getId());
            }
            if (autoSendTime != 0) {
                // schedule a new auto-send-draft task
                AutoSendDraftTask.scheduleTask(msg.getId(), mbox.getId(), autoSendTime);
            }
        }

        return generateResponse(zsc, ifmt, octxt, mbox, msg);
    }

    protected boolean schedulesAutoSendTask() {
        return true;
    }

    protected Element generateResponse(ZimbraSoapContext zsc, ItemIdFormatter ifmt, OperationContext octxt, Mailbox mbox, Message msg)
    throws ServiceException {
        int changeId = msg.getSavedSequence();
        while (true) {
            Element response = zsc.createElement(MailConstants.SAVE_DRAFT_RESPONSE);
            try {
                // FIXME: semi-inefficient -- this re-fetches the MimeMessage (but SaveDraft is called rarely)
                ToXML.encodeMessageAsMP(response, ifmt, octxt, msg, null, -1, true, true, null, true, false);
                return response;
            } catch (ServiceException e) {
                // problem writing the message structure to the response
                //   (this case generally means that the blob backing the MimeMessage disappeared halfway through)
                try {
                    msg = mbox.getMessageById(octxt, msg.getId());
                    if (msg.getSavedSequence() != changeId) {
                        // if the draft was re-saved and we failed because the old blob was deleted
                        //   out from under us, just fetch the new MimeMessage and try again
                        changeId = msg.getSavedSequence();
                        continue;
                    }
                } catch (NoSuchItemException nsie) {
                    // the draft has been deleted, so don't include draft data in the response
                    return zsc.createElement(MailConstants.SAVE_DRAFT_RESPONSE);
                }
                // we're kinda screwed here -- the draft was saved, but we weren't able to write the message structure
                //   and it's not clear what went wrong.  best we can do now is send back what we got and apologize.
                ZimbraLog.soap.warn("could not serialize full draft structure in response", e);
                return response;
            }
        }
    }
}
