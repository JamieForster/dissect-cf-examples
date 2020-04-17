/**
 * 
 */
package uk.ac.ljmu.fet.cs.cloud.examples.autoscaler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import hu.mta.sztaki.lpds.cloud.simulator.iaas.IaaSService;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;

/**
 * This autoscaler attempts to optimise for web-based traces by prioritising the
 * creation and destruction of virtual machines based on a buffer that increases
 * and decreases in size depending on its 'tier', which is changed dependent on
 * how many virtual machines are unused and destroyed, mimicking 'dropped'
 * requests from a web page.
 * 
 * @author Jamie Forster
 */
public class BufferTiersVI extends VirtualInfrastructure {

	/**
	 * Amount of virtual machines to be kept open for new jobs.
	 */
	private int vmBuffer = 16;

	/**
	 * vmBuffer multiplier
	 */
	private int bufferMultiplier = 1;

	/**
	 * a count of dropped/destroyed virtual machines
	 */
	private int droppedVMs;

	/**
	 * Keeps track of VMs in buffer, to that it will only destroy VMs that have been
	 * inactive for an hour
	 */
	private final HashMap<VirtualMachine, Integer> inactivePeriod = new HashMap<VirtualMachine, Integer>();

	/**
	 * Initialises the auto scaling mechanism
	 * 
	 * @param cloud the physical infrastructure to use to rent the VMs from
	 */
	public BufferTiersVI(IaaSService cloud) {
		super(cloud);
	}

	/**
	 * The auto scaling mechanism that is run regularly to determine if the virtual
	 * infrastructure needs some changes. The logic is the following:
	 * <ul>
	 * <li>the buffer is a preset number of virtual machines, with a multiplier
	 * attached</li>
	 * <li>first, create the initial buffer of virtual machines</li>
	 * <li>if the buffer is half full, request a new Virtual Machine and reduce the
	 * buffer's multiplier by 1 (minimum multiplier size: 1)</li>
	 * <li>if the number of Virtual Machines in the buffer is larger than the
	 * buffer's desired size, destroy the excess virtual machines in the buffer that
	 * have been inactive in the buffer for more than 1 hour</li>
	 * <li>if the amount of excess virtual machines destroyed is equal to the size
	 * of the buffer, increase the multiplier by 1.</li>
	 * </ul>
	 */
	@Override
	public void tick(long fires) {
		// re-initialise droppedVMs to 0
		droppedVMs = 0;

		final Iterator<String> kinds = vmSetPerKind.keySet().iterator();
		while (kinds.hasNext()) {
			final String kind = kinds.next();
			final ArrayList<VirtualMachine> vmset = vmSetPerKind.get(kind);

			if (vmset.isEmpty()) {
				// Create initial set of virtual machines based on buffer size.
				for (int i = 0; i < (vmBuffer * bufferMultiplier); i++) {
					requestVM(kind);
				}
			} else {
				// Determine if there are any virtual machines that are currently inactive
				ArrayList<VirtualMachine> bufferVMs = new ArrayList<VirtualMachine>();

				for (final VirtualMachine vm : vmset) {
					if (vm.underProcessing.isEmpty() && vm.toBeAdded.isEmpty()) {
						// No jobs running or queued by this virtual machine, it is currently inactive.
						bufferVMs.add(vm);
					}
				}

				if (bufferVMs.size() < (vmBuffer * bufferMultiplier) / 2) {
					// Buffer is half full, fill buffer with new VMs.
					for (int i = 0; i < (vmBuffer * bufferMultiplier) - bufferVMs.size(); i++) {
						requestVM(kind);
					}

					if (bufferMultiplier < 1) {
						// Minimum cap of 1.
						bufferMultiplier++;
					}
				} else if (bufferVMs.size() > (vmBuffer * bufferMultiplier)) {
					// Buffer is too full, destroy excess VMs to save power.
					for (int i = 0; i < bufferVMs.size() - (vmBuffer * bufferMultiplier); i++) {
						if (inactivePeriod.containsKey(bufferVMs.get(i))) {
							// Already in the hash map
							inactivePeriod.put(bufferVMs.get(i), (inactivePeriod.get(bufferVMs.get(i)) + 1));
						} else {
							// Not in the hash map
							inactivePeriod.put(bufferVMs.get(i), 1);
						}

						if (inactivePeriod.get(bufferVMs.get(i)) > 30) {
							// Inactive for 1 hour, destroy VM.
							destroyVM(bufferVMs.get(i));
							droppedVMs++;
						}
					}

					if (droppedVMs >= (vmBuffer * bufferMultiplier)) {
						// Too many VMs dropped, increase buffer tier size to reduce waste.
						bufferMultiplier++;
					}
				}
			}
		}
	}
}
