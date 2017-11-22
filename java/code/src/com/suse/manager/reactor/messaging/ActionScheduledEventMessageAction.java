/**
 * Copyright (c) 2016 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.suse.manager.reactor.messaging;

import com.redhat.rhn.common.messaging.EventMessage;
import com.redhat.rhn.domain.action.Action;
import com.redhat.rhn.domain.action.ActionFactory;
import com.redhat.rhn.frontend.events.AbstractDatabaseAction;

import com.suse.manager.webui.services.SaltServerActionService;

import org.apache.log4j.Logger;

/**
 * {@link com.redhat.rhn.common.messaging.MessageQueue} handler for
 * {@link ActionScheduledEventMessage} events signaling that a new
 * {@link com.redhat.rhn.domain.action.server.ServerAction} has been stored.
 *
 * The handler looks which servers are Salt systems and start the necessary
 * Salt jobs for the actions.
 */
public class ActionScheduledEventMessageAction extends AbstractDatabaseAction {

    /* Logger for this class */
    private static final Logger LOG = Logger
            .getLogger(ActionScheduledEventMessageAction.class);

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doExecute(EventMessage eventMessage) {
        ActionScheduledEventMessage event = (ActionScheduledEventMessage) eventMessage;
        Action action = ActionFactory.lookupById(event.getActionId());
        if (action != null) {
            LOG.debug("Action scheduled: " + action.getName());
            SaltServerActionService.INSTANCE.execute(action,
                    event.forcePackageListRefresh());
        }
        else {
            LOG.error("Action not found: " + event.getActionId());
        }
    }
}