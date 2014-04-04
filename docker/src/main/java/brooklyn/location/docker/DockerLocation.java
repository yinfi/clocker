/*
 * Copyright 2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.location.docker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.entity.group.DynamicCluster.NodePlacementStrategy;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.basic.AbstractLocation;
import brooklyn.location.basic.Machines;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.cloud.AvailabilityZoneExtension;
import brooklyn.location.dynamic.DynamicLocation;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.guava.Maybe;

public class DockerLocation extends AbstractLocation implements DockerVirtualLocation,
        MachineProvisioningLocation<MachineLocation>,
        DynamicLocation<DockerInfrastructure, DockerLocation> {

	private static final Logger LOG = LoggerFactory.getLogger(DockerLocation.class);

    @SetFromFlag("mutex")
    private Object mutex;

    @SetFromFlag("owner")
    private DockerInfrastructure infrastructure;

    @SetFromFlag("strategy")
    private NodePlacementStrategy strategy;

    @SetFromFlag("provisioner")
    private MachineProvisioningLocation<SshMachineLocation> provisioner;

    /* Mappings for provisioned locations */

    private final Set<SshMachineLocation> obtained = Sets.newHashSet();
    private final Multimap<SshMachineLocation, String> machines = HashMultimap.create();
    private final Map<String, SshMachineLocation> containers = Maps.newHashMap();

    public DockerLocation() {
        this(Maps.newLinkedHashMap());
    }

    public DockerLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    @Override
    public void init() {
        super.init();
        addExtension(AvailabilityZoneExtension.class, new DockerHostExtension(getManagementContext(), this));
    }

    @Override
    public void configure(Map properties) {
        if (mutex == null) {
            mutex = new Object[0];
        }
        super.configure(properties);
    }

    public MachineLocation obtain() throws NoMachinesAvailableException {
        return obtain(Maps.<String,Object>newLinkedHashMap());
    }

    @Override
    public MachineLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
        synchronized (mutex) {
            /*
            // Check context for entitiy implementing UsesJava interface
            Object context = flags.get(LocationConfigKeys.CALLER_CONTEXT.getName());
            if (context instanceof Entity) {
                List<Class<?>> implementations = Reflections.getAllInterfaces(context.getClass());
                boolean usesJava = Iterables.any(implementations, Predicates.<Class>equalTo(UsesJava.class));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Context {}: UsesJava {}", context.toString(), Boolean.toString(usesJava));
                }
                if (!usesJava) {
                    // Return an SshMachineLocation from the provisioner
                    SshMachineLocation machine = provisioner.obtain(flags);
                    obtained.add(machine);
                    return machine;
                }
            }
            */

            // Use the docker strategy to add a single JVM
            List<Location> dockerHosts = getExtension(DockerHostExtension.class).doGetAllSubLocations();
            List<Location> added = strategy.locationsForAdditions(null, dockerHosts, 1);
            DockerHostLocation machine = (DockerHostLocation) Iterables.getOnlyElement(added);
            DockerHost dockerHost = machine.getOwner();

            // Now wait until the JVM has started up
            Entities.waitForServiceUp(dockerHost, dockerHost.getConfig(DockerHost.START_TIMEOUT), TimeUnit.SECONDS);

            // Obtain a new Docker container location, save and return it
            DockerHostLocation location = dockerHost.getDynamicLocation();
            DockerContainerLocation container = location.obtain();
            Maybe<SshMachineLocation> deployed = Machines.findUniqueSshMachineLocation(dockerHost.getLocations());
            if (deployed.isPresent()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Storing container mapping: {}-{}", deployed.get(), container.getId());
                }
                machines.put(deployed.get(), container.getId());
                containers.put(container.getId(), deployed.get());
            }
            return container;
        }
    }

    @Override
    public MachineProvisioningLocation<MachineLocation> newSubLocation(Map<?, ?> newFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void release(MachineLocation machine) {
        if (provisioner != null) {
            synchronized (mutex) {
                if (machine instanceof DockerContainerLocation) {
                    String id = machine.getId();
                    SshMachineLocation ssh = containers.remove(id);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Request to release container mapping {}-{}", ssh, id);
                    }
                    if (ssh != null) {
                        machines.remove(ssh, id);
                        if (machines.get(ssh).isEmpty()) {
                            provisioner.release(ssh);
                        }
                    } else {
                        throw new IllegalArgumentException("Request to release "+machine+", but no SSH machine found");
                    }
                } else if (machine instanceof SshMachineLocation) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Request to release SSH machine {}", machine);
                    }
                    if (obtained.contains(machine)) {
                        provisioner.release((SshMachineLocation) machine);
                        obtained.remove(machine);
                    } else {
                        throw new IllegalArgumentException("Request to release "+machine+", but this machine is not currently allocated");
                    }
                } else {
                    throw new IllegalArgumentException("Request to release "+machine+", but location type is not supported");
                }
            }
        } else {
            throw new IllegalStateException("No provisioner available to release "+machine);
        }
    }

    @Override
    public Map<String,Object> getProvisioningFlags(Collection<String> tags) {
        return Maps.newLinkedHashMap();
    }

    public List<Entity> getDockerContainerList() {
        return infrastructure.getDockerContainerList();
    }

    public List<Entity> getDockerHostList() {
        return infrastructure.getDockerHostList();
    }

    public DockerInfrastructure getDockerInfrastructure() {
        return infrastructure;
    }

    @Override
    public ToStringHelper string() {
        return super.string()
                .add("infrastructure", infrastructure)
                .add("strategy", strategy);
    }

    @Override
    public DockerInfrastructure getOwner() {
        return infrastructure;
    }

}