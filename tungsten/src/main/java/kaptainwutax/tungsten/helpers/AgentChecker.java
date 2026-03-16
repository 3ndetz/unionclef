package kaptainwutax.tungsten.helpers;

import kaptainwutax.tungsten.agent.Agent;

/**
 * Helper class to check agent for certain conditions.
 */
public class AgentChecker {

	/**
     * Checks if agent is on ground and has less velocity then a given value.
     * 
     * @param agent Agent to be checked
     * @param minVelocity Agent needs to have less velocity then this to be considered stationary
     * @return true if agent is on ground and has less velocity then given value.
     */
	public static boolean isAgentStationary(Agent agent, double minVelocity) {
        return agent.velX < minVelocity && agent.velX > -minVelocity &&
               agent.velZ < minVelocity && agent.velZ > -minVelocity &&
               agent.onGround;
    }
	
	
}
