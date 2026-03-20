package kaptainwutax.tungsten.agent;

import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import net.minecraft.util.math.Vec2f;

public class AgentInput {
	

	public TungstenPlayerInput playerInput = TungstenPlayerInput.DEFAULT;
	protected Vec2f movementVector = Vec2f.ZERO;

	public Vec2f getMovementInput() {
		return this.movementVector;
	}

	public boolean hasForwardMovement() {
		return this.movementVector.y > 1.0E-5F;
	}

	public void jump() {
		this.playerInput = new TungstenPlayerInput(
			this.playerInput.forward(),
			this.playerInput.backward(),
			this.playerInput.left(),
			this.playerInput.right(),
			true,
			this.playerInput.sneak(),
			this.playerInput.sprint()
		);
	}

	private final Agent agent;

	public AgentInput(Agent agent) {
		this.agent = agent;
	}
	
	private static float getMovementMultiplier(boolean positive, boolean negative) {
		if (positive == negative) {
			return 0.0F;
		} else {
			return positive ? 1.0F : -1.0F;
		}
	}
	
	public void tick() {
		this.playerInput = new TungstenPlayerInput(
				this.agent.keyForward,
				this.agent.keyBack,
				this.agent.keyLeft,
				this.agent.keyRight,
				this.agent.keyJump,
				this.agent.keySneak,
				this.agent.sprinting
		);
		float f = getMovementMultiplier(this.playerInput.forward(), this.playerInput.backward());
		float g = getMovementMultiplier(this.playerInput.left(), this.playerInput.right());
		
		this.movementVector = new Vec2f(g, f);
	}
	
	public PathInput toPathInput() {
		return new PathInput(this.agent.keyForward, this.agent.keyBack, this.agent.keyRight, this.agent.keyLeft, this.agent.keyJump, this.agent.keySneak, this.agent.keySprint, this.agent.pitch, this.agent.yaw);
	}

}
