package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.agent.TungstenPlayerInput;

public class PathInput {

	public final boolean forward, back, right, left, jump, sneak, sprint;
	public final float pitch, yaw;

	public PathInput(boolean forward, boolean back, boolean right, boolean left, boolean jump, boolean sneak, boolean sprint, float pitch, float yaw) {
		this.forward = forward;
		this.back = back;
		this.right = right;
		this.left = left;
		this.jump = jump;
		this.sneak = sneak;
		this.sprint = sprint;
		this.pitch = pitch;
		this.yaw = yaw;
	}
	
	public TungstenPlayerInput getPlayerInput() {
		return new TungstenPlayerInput(forward, back, left, right, jump, sneak, sprint);
	}

	@Override
	public String toString() {
		
		StringBuilder string = new StringBuilder();

		string.append("{\n");
		string.append("forward: ");
		string.append(forward);
		string.append("\n");
		string.append("back: ");
		string.append(back);
		string.append("\n");
		string.append("right: ");
		string.append(right);
		string.append("\n");
		string.append("left: ");
		string.append(left);
		string.append("\n");
		string.append("jump: ");
		string.append(jump);
		string.append("\n");
		string.append("sneak: ");
		string.append(sneak);
		string.append("\n");
		string.append("sprint: ");
		string.append(sprint);
		string.append("\n");
		string.append("pitch: ");
		string.append(pitch);
		string.append("\n");
		string.append("yaw: ");
		string.append(yaw);
		string.append("\n");
		string.append("}");
		
		return string.toString();
	}
	
}
