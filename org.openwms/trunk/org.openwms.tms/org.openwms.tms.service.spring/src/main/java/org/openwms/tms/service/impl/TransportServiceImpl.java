/*
 * openwms.org, the Open Warehouse Management System.
 *
 * This file is part of openwms.org.
 *
 * openwms.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * openwms.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software. If not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.openwms.tms.service.impl;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.openwms.common.domain.Location;
import org.openwms.common.domain.LocationGroup;
import org.openwms.common.domain.TransportUnit;
import org.openwms.common.domain.values.Barcode;
import org.openwms.common.domain.values.Problem;
import org.openwms.core.integration.GenericDao;
import org.openwms.core.service.spring.EntityServiceImpl;
import org.openwms.core.service.voter.DecisionVoter;
import org.openwms.core.service.voter.DeniedException;
import org.openwms.tms.domain.order.TransportOrder;
import org.openwms.tms.domain.values.PriorityLevel;
import org.openwms.tms.domain.values.TransportOrderState;
import org.openwms.tms.integration.TransportOrderDao;
import org.openwms.tms.service.TransportOrderService;
import org.openwms.tms.service.exception.TransportOrderServiceException;
import org.openwms.tms.util.TransportOrderUtil;
import org.openwms.tms.util.event.TransportServiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A TransportService.
 * 
 * @author <a href="mailto:scherrer@openwms.org">Heiko Scherrer</a>
 * @version $Revision$
 * @since 0.1
 * @see org.openwms.core.service.spring.EntityServiceImpl
 * @see org.openwms.tms.service.TransportOrderService
 */
@Service("transportService")
@Transactional
public class TransportServiceImpl extends EntityServiceImpl<TransportOrder, Long> implements
        TransportOrderService<TransportOrder> {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private TransportOrderDao dao;

    @Autowired
    @Qualifier("transportUnitDao")
    private GenericDao<TransportUnit, Long> transportUnitDao;

    @Autowired
    @Qualifier("locationDao")
    private GenericDao<Location, Long> locationDao;

    @Autowired
    @Qualifier("locationGroupDao")
    private GenericDao<LocationGroup, Long> locationGroupDao;

    // 0..* voters
    @Autowired(required = false)
    @Qualifier("targetAcceptedVoter")
    private List<DecisionVoter<RedirectVote>> redirectVoters;

    @SuppressWarnings("unused")
    @PostConstruct
    private void init() {
        super.dao = dao;
    }

    /**
     * Get an instance of {@link TransportOrderDao}.
     * 
     * @return the dao.
     */
    protected TransportOrderDao getDao() {
        return dao;
    }

    /**
     * {@inheritDoc}
     * 
     * Just delegates to the dao.
     * 
     * @see org.openwms.tms.service.TransportOrderService#getTransportsToLocationGroup(org.openwms.common.domain.LocationGroup)
     */
    @Override
    public int getTransportsToLocationGroup(LocationGroup locationGroup) {
        return dao.getNumberOfTransportOrders(locationGroup);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.openwms.tms.service.TransportOrderService#createTransportOrder(org.openwms.common.domain.values.Barcode,
     *      org.openwms.common.domain.LocationGroup,
     *      org.openwms.tms.domain.values.PriorityLevel)
     */
    @Override
    public TransportOrder createTransportOrder(Barcode barcode, LocationGroup targetLocationGroup,
            PriorityLevel priority) {
        return createTransportOrder(barcode, targetLocationGroup, null, priority);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.openwms.tms.service.TransportOrderService#createTransportOrder(org.openwms.common.domain.values.Barcode,
     *      org.openwms.common.domain.Location,
     *      org.openwms.tms.domain.values.PriorityLevel)
     */
    @Override
    public TransportOrder createTransportOrder(Barcode barcode, Location targetLocation, PriorityLevel priority) {
        return createTransportOrder(barcode, null, targetLocation, priority);
    }

    /**
     * {@inheritDoc}
     * 
     * Checks that all necessary data to create a TransportOrder is given, does
     * not do any logical checks, whether a target is blocked or a
     * {@link TransportOrder} for the {@link TransportUnit} exist.
     * 
     * @see org.openwms.tms.service.TransportOrderService#createTransportOrder(org.openwms.common.domain.values.Barcode,
     *      org.openwms.common.domain.LocationGroup,
     *      org.openwms.common.domain.Location,
     *      org.openwms.tms.domain.values.PriorityLevel)
     * @throws TransportOrderServiceException
     *             when the barcode is <code>null</code> or no transportUnit
     *             with barcode can be found or no target can be found.
     */
    @Override
    public TransportOrder createTransportOrder(Barcode barcode, LocationGroup targetLocationGroup,
            Location targetLocation, PriorityLevel priority) {
        if (logger.isDebugEnabled()) {
            logger.debug("Create TransportOrder with Barcode " + barcode + ", to LocationGroup " + targetLocationGroup
                    + ", to Location " + targetLocation + ", with Priority " + priority + " ...");
        }
        if (barcode == null) {
            throw new TransportOrderServiceException("Barcode cannot be null when creating a TransportOrder");
        }
        TransportUnit transportUnit = transportUnitDao.findByUniqueId(barcode);
        if (transportUnit == null) {
            throw new TransportOrderServiceException("TransportUnit with Barcode " + barcode + " not found");
        }
        TransportOrder transportOrder = new TransportOrder();
        transportOrder.setTransportUnit(transportUnit);

        Location tLocation = null;
        if (targetLocation != null) {
            tLocation = locationDao.findById(targetLocation.getId());
            transportOrder.setTargetLocation(tLocation);
        }
        LocationGroup tLocationGroup = null;
        if (targetLocationGroup != null) {
            tLocationGroup = locationGroupDao.findById(targetLocationGroup.getId());
            transportOrder.setTargetLocationGroup(tLocationGroup);
        }
        if (tLocation == null && tLocationGroup == null) {
            throw new TransportOrderServiceException(
                    "Either a Location or a LocationGroup must be exist to create a TransportOrder");
        }
        if (priority != null) {
            transportOrder.setPriority(priority);
        }
        dao.persist(transportOrder);
        ctx.publishEvent(new TransportServiceEvent(transportOrder.getTransportUnit(),
                TransportServiceEvent.TYPE.TRANSPORT_CREATED));
        return transportOrder;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.openwms.tms.service.TransportOrderService#cancelTransportOrders(java.util.List)
     */
    @Override
    public List<Integer> cancelTransportOrders(List<Integer> ids, TransportOrderState state) {
        List<Integer> failure = new ArrayList<Integer>(ids.size());
        List<TransportOrder> transportOrders = dao.findByIds(TransportOrderUtil.getLongList(ids));
        for (TransportOrder transportOrder : transportOrders) {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Trying to turn TransportOrder [" + transportOrder.getId() + "] into: " + state);
                }
                transportOrder.setState(state);
                ctx.publishEvent(new TransportServiceEvent(transportOrder.getId(), TransportOrderUtil
                        .convertToEventType(state)));
            } catch (IllegalStateException ise) {
                logger.error("Could not turn TransportOrder: [" + transportOrder.getId() + "] into " + state
                        + " with reason : " + ise.getMessage());
                Problem problem = new Problem(ise.getMessage());
                transportOrder.setProblem(problem);
                failure.add(transportOrder.getId().intValue());
            }
        }
        return failure;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.openwms.tms.service.TransportOrderService#redirectTransportOrders(java.util.List,
     *      org.openwms.common.domain.LocationGroup,
     *      org.openwms.common.domain.Location)
     * @throws TransportOrderServiceException
     *             when both targets are <code>null</code>
     */
    @Override
    public List<Integer> redirectTransportOrders(List<Integer> ids, LocationGroup targetLocationGroup,
            Location targetLocation) {
        LocationGroup tLocationGroup = null;
        Location tLocation = null;
        if (null != targetLocationGroup) {
            tLocationGroup = locationGroupDao.findByUniqueId(targetLocationGroup.getName());
        }
        if (null != targetLocation) {
            tLocation = locationDao.findByUniqueId(targetLocation.getLocationId());
        }
        if (null == tLocation && null == tLocationGroup) {
            throw new TransportOrderServiceException(
                    "Both targets can may not be null, at least one target must be specififed");
        }
        List<Integer> failure = new ArrayList<Integer>(ids.size());
        List<TransportOrder> transportOrders = dao.findByIds(TransportOrderUtil.getLongList(ids));
        for (TransportOrder transportOrder : transportOrders) {
            try {
                if (null != redirectVoters) {
                    voteOnVoters(new RedirectVote(targetLocationGroup, targetLocation));
                }
                if (null != tLocationGroup) {
                    transportOrder.setTargetLocationGroup(tLocationGroup);
                }
                if (null != tLocation) {
                    transportOrder.setTargetLocation(tLocation);
                }
            } catch (DeniedException de) {
                logger.error("Could not redirect TransportOrder with ID [" + transportOrder.getId() + "], reason is: "
                        + de.getMessage());
                Problem problem = new Problem(de.getMessage());
                transportOrder.setProblem(problem);
                failure.add(transportOrder.getId().intValue());
            }
        }
        return failure;
    }

    private void voteOnVoters(RedirectVote vote) throws DeniedException {
        for (DecisionVoter<RedirectVote> voter : redirectVoters) {
            voter.voteFor(vote);
        }
    }

}