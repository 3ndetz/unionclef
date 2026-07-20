package kaptainwutax.tungsten;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import kaptainwutax.tungsten.render.Renderer;

public class TungstenModRenderContainer {

	public static Collection<Renderer> BLOCK_PATH_RENDERER = Collections.synchronizedCollection(new ArrayList<>());
	public static Collection<Renderer> RUNNING_PATH_RENDERER = Collections.synchronizedCollection(new ArrayList<>());
	public static Collection<Renderer> RENDERERS = Collections.synchronizedCollection(new ArrayList<>());
	public static Collection<Renderer> ERROR = Collections.synchronizedCollection(new ArrayList<>());
	public static Collection<Renderer> TEST = Collections.synchronizedCollection(new ArrayList<>());
	public static Collection<Renderer> COMBAT_TRAJECTORY = Collections.synchronizedCollection(new ArrayList<>());
	/** Mining plan: boxes around blocks queued for breaking + the one being mined. */
	public static Collection<Renderer> BREAK_PLAN = Collections.synchronizedCollection(new ArrayList<>());
}
