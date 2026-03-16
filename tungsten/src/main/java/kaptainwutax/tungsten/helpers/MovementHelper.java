package kaptainwutax.tungsten.helpers;

import static kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary.NO;
import static kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary.YES;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import net.minecraft.block.AzaleaBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

public class MovementHelper {
	
	 public static Ternary canWalkOnBlockState(BlockState state) {
	        Block block = state.getBlock();
	        if (BlockShapeChecker.isBlockNormalCube(state) && block != Blocks.MAGMA_BLOCK && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
	            return YES;
	        }
	        if (block instanceof AzaleaBlock) {
	            return YES;
	        }
	        if (block == Blocks.LADDER || block == Blocks.VINE) { // TODO reconsider this
	            return YES;
	        }
	        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH) {
	            return YES;
	        }
	        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
	            return YES;
	        }
	        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
	            return YES;
	        }
	        if (block instanceof StairsBlock) {
	            return YES;
	        }
	        if (block instanceof SlabBlock) {
	            return YES;
	        }
	        return NO;
	    }
	 

	    public static boolean wasCleared(WorldView world, BlockPos start, BlockPos end) {
	    	return wasCleared(world, start, end, null, null);
	    }
	    
	    public static boolean wasCleared(WorldView world, BlockPos start, BlockPos end, BlockNode startNode, BlockNode endNode) { 
			int x1 = start.getX();
		    int y1 = start.getY();
		    int z1 = start.getZ();
		
		    int x2 = end.getX();
		    int y2 = end.getY();
		    int z2 = end.getZ();
		    
		    boolean isJumpingOneBlock = y2-y1 == 1;
			TungstenModRenderContainer.TEST.clear();
			BlockPos.Mutable currPos = new BlockPos.Mutable();
			int x = x1;
	        int y = isJumpingOneBlock ? y1+1 : y1;
	        int z = z1;

	    	int dx = start.getX() - x2;
	    	int dz = start.getZ() - z2;
	    	double distance = Math.sqrt(dx * dx + dz * dz);
	        boolean isMovingOnXAxis = x1-x2 == 0;
	        boolean isMovingOnZAxis = z1-z2 == 0;
	        boolean shouldCheckNeo = start.isWithinDistance(end, 4.2) && true;
		    boolean isNeoPossible = isNeoPossible(world, isMovingOnXAxis, isMovingOnZAxis, start, end, isJumpingOneBlock, endNode);
	      boolean shouldRender = false;
	      boolean shouldSlow = false;
	      if (shouldSlow) {
			TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x1, y1, z1), new Vec3d(1.0D, 1.0D, 1.0D), Color.GREEN));
			TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x2, y2, z2), new Vec3d(1.0D, 1.0D, 1.0D), Color.GREEN));
	      }

	        while (x != x2 || y != y2 || z != z2) {
	        	if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
	            
	            
	            currPos.set(x, y, z);
	        	if (isJumpingOneBlock && world.getBlockState(currPos.down()).getBlock() instanceof FenceBlock) return false;
	        	if (isJumpingOneBlock && world.getBlockState(currPos).getBlock() instanceof SlabBlock) return false;
	            if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
	            	if (shouldCheckNeo) {
		            	if (!isNeoPossible){
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
							return false;
						}
	            	} else return false;
				} else {
					if (shouldRender) {
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
					}
				}
	            
	            if (z < z2) {
	                z++;
	            } else if (z > z2) {
	                z--;
	            }

	            currPos.set(x, y, z);
	            if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
	            	if (shouldCheckNeo) {
		            	if (!isNeoPossible){
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
							return false;
						}
	            	} else return false;
				} else {
					if (shouldRender) {
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
					}
				}
//	        }
	            if (x < x2) {
	                x++;
	            } else if (x > x2) {
	                x--;
	            }
	            currPos.set(x, y, z);

	            if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
	            	if (shouldCheckNeo) {
		            	if (!isNeoPossible){
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
							return false;
						}
	            	} else return false;
				} else {
					if (shouldRender) {
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
					}
				}

	            if (y < y2) {
	                y++;
	            } else if (y > y2) {
	                y--;
	            }
	            currPos.set(x, y, z);
	            
	            if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
	            	if (shouldCheckNeo) {
		            	if (!isNeoPossible){
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
							return false;
						}
	            	} else return false;
				} else {
					if (shouldRender) {
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
					}
				}
	        }
			if (shouldSlow) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ignored) {}
			}

			return true;
		}
	    
	    public static boolean isNeoPossible(WorldView world, boolean isMovingOnXAxis, boolean isMovingOnZAxis, BlockPos startPos, BlockPos endPos, boolean isJumpingOneBlock) {
	    	return isNeoPossible(world, isMovingOnXAxis, isMovingOnZAxis, startPos, endPos, isJumpingOneBlock, null);
	    }
	    
	    public static boolean isNeoPossible(WorldView world, boolean isMovingOnXAxis, boolean isMovingOnZAxis, BlockPos startPos, BlockPos endPos, boolean isJumpingOneBlock, BlockNode node) {
	    	int endX = endPos.getX();
	    	int endY = endPos.getY();
	    	int endZ = endPos.getZ();
	    	int x = startPos.getX();
	    	int y = startPos.getY();
	    	int z = startPos.getZ();
	    	int dx = startPos.getX() - endX;
	    	int dz = startPos.getZ() - endZ;
	    	double distance = Math.sqrt(dx * dx + dz * dz);
	        boolean shouldRender = false;
	        boolean shouldSlow = false;
	    	boolean isCornerXPossible = true;
	    	boolean isCornerZPossible = true;
	        boolean isLadder = world.getBlockState(endPos).getBlock() instanceof LadderBlock
	        		|| world.getBlockState(endPos.down()).getBlock() instanceof LadderBlock
	        		|| world.getBlockState(startPos).getBlock() instanceof LadderBlock
	    			|| world.getBlockState(startPos.down()).getBlock() instanceof LadderBlock;
	    	BlockPos.Mutable currPos = new BlockPos.Mutable();
	    	int count = 0;
	    	if (isMovingOnXAxis && !isLadder) {
	        	if (world.getBlockState(startPos).getBlock() instanceof LadderBlock
	        			|| world.getBlockState(startPos.down()).getBlock() instanceof LadderBlock
	        			|| BlockStateChecker.isOpenTrapdoor(world.getBlockState(startPos.down()))) {
	        		return false;
	        	}
	        	if (startPos.getZ() > endZ) {
		        	// West
					x = startPos.getX();
					y = startPos.getY();
					z = startPos.getZ();
		        	int neoX = x-1;
		        	int currZ = endZ > z ? z-1 : z+1;
		        	while (currZ != endZ) {
		        		if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
		        		if (count > 5) return false;
		        		count++;
		            	currPos.set(neoX, y-1, currZ);
		            	if (!world.getBlockState(currPos).isAir()) return false;
		            	if (z < endZ) {
		            		currZ++;
		                } else if (z > endZ) {
		                	currZ--;
		                }
		            	currPos.set(neoX, y, currZ);
		            	if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		    				}
		    			}
		        	}
	    		}
	        	if (startPos.getZ() < endZ) {
	        		count = 0;
		        	// East
					x = startPos.getX();
					y = startPos.getY();
					z = startPos.getZ();
		        	int neoX = x+1;
		        	int currZ = endZ > z ? z-1 : z+1;
		        	while (currZ != endZ) {
		        		if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
		        		if (count > 5) return false;
		        		count++;
		            	currPos.set(neoX, y-1, currZ);
		            	if (!world.getBlockState(currPos).isAir()) return false;
		            	if (z < endZ) {
		            		currZ++;
		                } else if (z > endZ) {
		                	currZ--;
		                }
		            	currPos.set(neoX, y, currZ);
		            	if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		    				}
		    			}
		        	}
	    		}
	    		isCornerXPossible = false;
	    		isCornerZPossible = false;
	        } else if (isMovingOnZAxis && !isLadder) {
	        	if (world.getBlockState(startPos).getBlock() instanceof LadderBlock
	        			|| world.getBlockState(startPos.down()).getBlock() instanceof LadderBlock
	        			|| BlockStateChecker.isOpenTrapdoor(world.getBlockState(startPos.down()))) {
	        		return false;
	        	}
	        	if (startPos.getX() < endX) {
	        		count = 0;
	        		// South
					x = startPos.getX();
					y = startPos.getY();
					z = startPos.getZ();
		        	int neoZ = z+1;
		        	int currX =  endX > x ? x-1 : x+1;
		        	while (currX != endX) {
		        		if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
		        		if (count > 5) return false;
		        		count++;
		            	currPos.set(currX, y-1, neoZ);
		            	if (!world.getBlockState(currPos).isAir()) {
		            		break;
		            	}
		            	if (x < endX) {
		            		currX++;
		                } else if (x > endX) {
		                	currX--;
		                }
		            	currPos.set(currX, y, neoZ);
		            	if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		            		break;
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		    				}
		    			}
		        	}
		        	
	        	}
	        	if (startPos.getX() > endX) {
	        		count = 0;
		        	// North
					x = startPos.getX();
					y = startPos.getY();
					z = startPos.getZ();
		        	int neoZ = z-1;
		        	int currX =  endX > x ? x-1 : x+1;
		        	while (currX != endX) {
		        		if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
		        		if (count > 5) return false;
		        		count++;
		            	currPos.set(currX, y-1, neoZ);
		            	if (!world.getBlockState(currPos).isAir()) {
		            		break;
		            	}
		            	if (x < endX) {
		            		currX++;
		                } else if (x > endX) {
		                	currX--;
		                }
		            	currPos.set(currX, y, neoZ);
		            	if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		            		break;
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY(), currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(currPos.getX(), currPos.getY()+1, currPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		    				}
		    			}
		        	}
	        	}

	    		isCornerXPossible = false;
	    		isCornerZPossible = false;
	        }
	    	if (isCornerXPossible || isCornerZPossible) {
					if (shouldRender) {
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(endX, endY, endZ), new Vec3d(1.0D, 1.0D, 1.0D), Color.GREEN));
						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(startPos.getX(), startPos.getY(), startPos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.GREEN));
					}
					x = startPos.getX();
					y = startPos.getY();
					z = startPos.getZ();
					boolean isEdgeOnX = endX - x < 3;
					boolean isEdgeOnZ = endZ - z < 3;
					if (isEdgeOnZ)
		        	while (x != endX || y != endY || z != endZ) {
		//        		if (distance >= 2) return false;
		            	if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
		                
		
						if (isEdgeOnZ) {
							if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
							}
							currPos.set(x, y, z);
							if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			            		if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
			            		isCornerXPossible = false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
							if (x == endX) {
								if (z < endZ) {
				                    z++;
				                } else if (z > endZ) {
				                    z--;
				                }
			
				                currPos.set(x, y, z);
								if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
				            		if (shouldRender) {
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										if (shouldSlow) {
											try {
												Thread.sleep(450);
											} catch (InterruptedException ignored) {}
										}
				            		}
				            		isCornerZPossible = false;
				    			} else {
				    				if (shouldRender) {
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    				}
				    			}
							} else {
								if (x < endX) {
				                    x++;
				                } else if (x > endX) {
				                    x--;
				                }
			
				                currPos.set(x, y, z);
								if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
				            		if (shouldRender) {
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										if (shouldSlow) {
											try {
												Thread.sleep(450);
											} catch (InterruptedException ignored) {}
										}
				            		}
				            		isCornerZPossible = false;
				    			} else {
				    				if (shouldRender) {
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    				}
				    			}
							}
								
						}
		        		if (y < endY) {
		                    y++;
		                } else if (y > endY) {
		                    y--;
		                }
		        		currPos.set(x, y, z);
						if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		            		isCornerXPossible = false;
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    				}
		    			}
		        		}
					else isCornerZPossible = false;
						x = startPos.getX();
						y = startPos.getY();
						z = startPos.getZ();
						if (isEdgeOnX)
						while (x != endX || y != endY || z != endZ) {
			            	if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
							
						if (isEdgeOnX) {
							if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
							}
							currPos.set(x, y, z);
							if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			            		if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
			            		isCornerXPossible = false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
							if (z == endZ) {
								if (x < endX) {
				                    x++;
				                } else if (x > endX) {
				                    x--;
				                }
			
				                currPos.set(x, y, z);
								if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
				            		if (shouldRender) {
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										if (shouldSlow) {
											try {
												Thread.sleep(450);
											} catch (InterruptedException ignored) {}
										}
				            		}
				            		isCornerXPossible = false;
				    			} else {
				    				if (shouldRender) {
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    				}
				    			}
							} else {
								if (z < endZ) {
				                    z++;
				                } else if (z > endZ) {
				                    z--;
				                }
			
				                currPos.set(x, y, z);
								if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
				            		if (shouldRender) {
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
										if (shouldSlow) {
											try {
												Thread.sleep(450);
											} catch (InterruptedException ignored) {}
										}
				            		}
				            		isCornerXPossible = false;
				    			} else {
				    				if (shouldRender) {
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
				    				}
				    			}
							}
						}
						
		
		        		if (y < endY) {
		                    y++;
		                } else if (y > endY) {
		                    y--;
		                }
		        		currPos.set(x, y, z);
						if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
		            		if (shouldRender) {
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
								if (shouldSlow) {
									try {
										Thread.sleep(450);
									} catch (InterruptedException ignored) {}
								}
		            		}
		            		isCornerXPossible = false;
		    			} else {
		    				if (shouldRender) {
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
		    				}
		    			}
						}
						else isCornerXPossible = false;
	    			}
//	                System.out.println(x + " " + endX);
//	                System.out.println(y + " " + endY);
//	                System.out.println(z + " " + endZ);
//	                System.out.println(isEdgeOnX);
//	                System.out.println(isEdgeOnZ);
					
					

						x = startPos.getX();
						y = startPos.getY();
						z = startPos.getZ();
						TungstenModRenderContainer.TEST.clear();
						if (distance < 2) {
			        	while (x != endX || y != endY || z != endZ) {

			            	if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
			                currPos.set(x-1, y, z-1);
			                if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			            		if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
								return false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
			                if (x < endX) {
			                    x++;
			                } else if (x > endX) {
			                    x--;
			                }
			
			                currPos.set(x, y, z);
			                if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			            		if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
								return false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
			            	if (y < endY) {
			            		if (y < endY) {
				                    y++;
				                } else if (y > endY) {
				                    y--;
				                }
			            		currPos.set(x, y, z);
			                    if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			                		if (shouldRender) {
			    						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
			    						TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
			    						if (shouldSlow) {
			    							try {
			    								Thread.sleep(450);
			    							} catch (InterruptedException ignored) {}
			    						}
			                		}
			    					return false;
			        			} else {
			        				if (shouldRender) {
			        					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			        					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			        				}
			        			}
			            	}
			                if (x < endX) {
			                    x++;
			                } else if (x > endX) {
			                    x--;
			                }
			
			                currPos.set(x, y, z);
			                if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			            		if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
								return false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
			                
			                if (z < endZ) {
			                    z++;
			                } else if (z > endZ) {
			                    z--;
			                }
			                currPos.set(x, y, z);
			
			                if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			                	if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x+0.5, y, z+0.5), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x+0.5, y+1, z+0.5), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
								return false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
			
			                if (y < endY) {
			                    y++;
			                } else if (y > endY) {
			                    y--;
			                }
			                currPos.set(x, y, z);
			                
			                if (isObscured(world, currPos, isJumpingOneBlock, distance == 1)) {
			                	if (shouldRender) {
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.RED));
									if (shouldSlow) {
										try {
											Thread.sleep(450);
										} catch (InterruptedException ignored) {}
									}
			            		}
								return false;
			    			} else {
			    				if (shouldRender) {
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    					TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(x, y+1, z), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
			    				}
			    			}
			            }
			        	return true;
					}
				
	    		if (shouldSlow) {
	    			try {
	    				Thread.sleep(850);
	    			} catch (InterruptedException ignored) {}
	    		}
	    	
	    	
	    	return isCornerXPossible || isCornerZPossible;
	    }
	    
	    public static boolean isObscured(WorldView world, BlockPos pos, boolean isJumpingUp, boolean isJumpingOneBlock) {
	    	BlockState stateBelow = world.getBlockState(pos.down());
	    	BlockState state = world.getBlockState(pos);
		    BlockState aboveState = world.getBlockState(pos.up());

		    Block belowBlock = stateBelow.getBlock();
		    Block block = state.getBlock();
		    Block aboveBlock = aboveState.getBlock();

		    boolean isSlabBelow = belowBlock instanceof SlabBlock;
		    
		    boolean isFullCube = state.isFullCube(world, pos);
		    boolean isSlab = block instanceof SlabBlock;
		    boolean isLeaves = block instanceof LeavesBlock;
		    boolean isStairs = block instanceof StairsBlock;
		    boolean isLava = block == Blocks.LAVA;
		
		    boolean isBlockConnected = BlockStateChecker.isConnected(pos, world);
		    
	        boolean isAboveFullCube = aboveState.isFullCube(world, pos.up());
	        boolean isAboveSlab = aboveBlock instanceof SlabBlock;
	        boolean isAboveLeaves = aboveBlock instanceof LeavesBlock;
		    boolean isAboveStairs = aboveBlock instanceof StairsBlock;
		    boolean isAboveBlockConnected = BlockStateChecker.isConnected(pos.up(), world);
		    
		    boolean isAboveX2Leaves =  world.getBlockState(pos.up(2)).getBlock() instanceof LeavesBlock;
	    	
	    	if (isJumpingUp && !world.getBlockState(pos.up(2)).isAir()) return true;

		    if (isJumpingUp && isJumpingOneBlock && BlockStateChecker.isBottomSlab(stateBelow) && state.isAir() && aboveState.isAir()) return false;
		    
		    if (isJumpingUp && isJumpingOneBlock && isStairs && aboveState.isAir() && !isAboveLeaves && !isAboveX2Leaves && world.getBlockState(pos.up(2)).isAir()) return false;
		    if (isJumpingUp && isJumpingOneBlock && isFullCube && aboveState.isAir() && world.getBlockState(pos.up(2)).isAir()) return false;
		    
		    
		    if (isLava || isLeaves || isAboveLeaves || isFullCube || isAboveFullCube
		    		|| isStairs || isAboveStairs) return true;
		    
		    // TODO: fix corner jump issue from slab to slab, removing the line below causes bot to think it can go through a wall made of slabs
//		    if (isSlabBelow || isAboveSlab) return true;
		    
		    if (isBlockConnected || isAboveBlockConnected) return true;
//		    if (!state.isAir() || !aboveState.isAir()) return true;
		    if (isLeaves || isAboveLeaves) return true;
//			TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(pos.getX(), pos.getY()+1, pos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
//			TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(pos.getX(), pos.getY(), pos.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
//			try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		    TungstenModRenderContainer.TEST.clear();
		    
		    
		    return false;
	    }
	    
	    public static double getSlimeBounceHeight(double startHeight) {
	    	return -0.0011 * Math.pow(startHeight, 2) + 0.43529 * startHeight + 1.7323;
	    }
	
}
