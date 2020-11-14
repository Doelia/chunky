package se.llbit.math;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Grid {
  private static final int GRID_FORMAT_VERSION = 1;

  /**
   * Holds a 3D grid of blocks cube
   * Each cell of the grid holds the position of the emitters present in this cell and in neighboring cells
   * As such when we want to sample emitters close from an intersection point, we only have to look at
   * the cell where this intersection falls in and we will find every emitters we are interested in.
   * The reason we need to hold emitter of neighboring cells is because emitters a fex block away from the intersection point
   * to have an effect even if it falls in a different cell.
   * With this every emitters away for cellSize or less blocks from the intersection points will always be found.
   * The maximum distance where an emitter can be found in some cases is 2*cellSize-1 blocks away.
   */
  public static class EmitterPosition {
    public EmitterPosition(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }

    public int x, y, z;
  }

  private final int cellSize;
  private List<EmitterPosition> emitterPositions = new ArrayList<>(); // Stored as an object array, may need to be flattened later to improve memory usage

  // Cells are only used during the grid preparation
  private static class Cell {
    public List<Integer> indexes = new ArrayList<>();
  }

  // This array is the concatenation of every index of EmitterPosition for every cell
  private int[] positionIndexes;
  // This array holds 2 ints per cell, the index to the start of its section
  // in the positionIndexes array and the size of its section
  private int[] constructedGrid;
  // This way of storing the data is more difficult to manipulate but more
  // memory efficient by virtue of only having 2 flat arrays

  // Only used during construction
  private int minX, maxX, minY, maxY, minZ, maxZ;

  private int offsetX, sizeX, offsetY, sizeY, offsetZ, sizeZ;

  public Grid(int cellSize) {
    this.cellSize = cellSize;
    minX = maxX = minY = maxY = minZ = maxZ = -1;
  }

  public void addEmitter(int x, int y, int z) {
    emitterPositions.add(new EmitterPosition(x, y, z));
    if(minX == -1 || x < minX)
      minX = x;
    if(maxX == -1 || x  > maxX)
      maxX = x;
    if(minY == -1 || y < minY)
      minY = y;
    if(maxY == -1 || y > maxY)
      maxY = y;
    if(minZ == -1 || z < minZ)
      minZ = z;
    if(maxZ == -1 || z > maxZ)
      maxZ = z;
  }

  private int cellIndex(int x, int y, int z) {
    return (((y - offsetY) * sizeX) + (x - offsetX)) * sizeZ + (z - offsetZ);
  }

  private boolean isOutOfBounds(int x, int y, int z)
  {
    return x < offsetX || x >= sizeX + offsetX
            || y < offsetY || y >= sizeY + offsetY
            || z < offsetZ || z >= sizeZ + offsetZ;
  }

  /**
   * Needs to be call when every emitter has been added
   * Builds the grid itself
   */
  public void prepare() {
    offsetX = minX / cellSize - 1;
    offsetY = minY / cellSize - 1;
    offsetZ = minZ / cellSize - 1;
    sizeX = maxX / cellSize - minX / cellSize + 3;
    sizeY = maxY / cellSize - minY / cellSize + 3;
    sizeZ = maxZ / cellSize - minZ / cellSize + 3;

    Cell[] gridDuringConstruction = new Cell[sizeX * sizeY * sizeZ];

    int numberOfPositionIndex = 0;

    for(int i = 0; i < emitterPositions.size(); ++i) {
      EmitterPosition pos = emitterPositions.get(i);
      int gridX = pos.x / cellSize;
      int gridY = pos.y / cellSize;
      int gridZ = pos.z / cellSize;
      // Add the emitter to its cell and all neighboring cells
      for(int dy = -1; dy <= 1; ++dy) {
        for(int dx = -1; dx <= 1; ++dx) {
          for(int dz = -1; dz <= 1; ++dz) {
            int x = gridX+dx;
            int y = gridY+dy;
            int z = gridZ+dz;
            if(!isOutOfBounds(x, y, z)) {
              int index = cellIndex(x, y, z);
              if(gridDuringConstruction[index] == null)
                gridDuringConstruction[index] = new Cell();
              gridDuringConstruction[index].indexes.add(i);
              ++numberOfPositionIndex;
            }
          }
        }
      }
    }

    positionIndexes = new int[numberOfPositionIndex];
    constructedGrid = new int[sizeX * sizeY * sizeZ * 2];
    int constructedGridCurrentIndex = 0;

    for(int i = 0; i < gridDuringConstruction.length; ++i) {
      if(gridDuringConstruction[i] == null) {
        constructedGrid[2*i] = constructedGridCurrentIndex;
        constructedGrid[2*i+1] = 0;
        continue;
      }

      int numberOfIndexes = gridDuringConstruction[i].indexes.size();
      for(int j = 0; j < numberOfIndexes; ++j) {
        positionIndexes[constructedGridCurrentIndex+j] = gridDuringConstruction[i].indexes.get(j);
      }
      constructedGrid[2*i] = constructedGridCurrentIndex;
      constructedGrid[2*i+1] = numberOfIndexes;
      constructedGridCurrentIndex += numberOfIndexes;
    }
  }

  /**
   * Returns the position of an emitter not far from the point given in world coordinates
   * or null if no such emitter exist
   */
  public EmitterPosition sampleEmitterPosition(int x, int y, int z, Random random) {
    int gridX = x / cellSize;
    int gridY = y / cellSize;
    int gridZ = z / cellSize;

    if(isOutOfBounds(gridX, gridY, gridZ))
      return null;

    int index = cellIndex(gridX, gridY, gridZ);

    int start = constructedGrid[2*index];
    int size = constructedGrid[2*index+1];

    if(size == 0)
      return null;
    int randomIndex = random.nextInt(size);
    int emitterIndex = positionIndexes[start+randomIndex];
    return emitterPositions.get(emitterIndex);
  }

  /**
   * Get the list of emitters position close from a given point
   */
  public List<EmitterPosition> getEmitterPositions(int x, int y, int z) {
    int gridX = x / cellSize;
    int gridY = y / cellSize;
    int gridZ = z / cellSize;

    List<EmitterPosition> pos = new ArrayList<>();
    if(isOutOfBounds(gridX, gridY, gridZ))
      return pos;

    int index = cellIndex(gridX, gridY, gridZ);
    int start = constructedGrid[2*index];
    int size = constructedGrid[2*index+1];
    for(int i = 0; i < size; ++i) {
      pos.add(emitterPositions.get(positionIndexes[start+i]));
    }
    return pos;
  }

  /**
   * Stores the grid in the given stream
   * @param out The output stream
   */
  public void store(DataOutputStream out) throws IOException {
    out.writeInt(GRID_FORMAT_VERSION);

    out.writeInt(cellSize);
    out.writeInt(offsetX);
    out.writeInt(sizeX);
    out.writeInt(offsetY);
    out.writeInt(sizeY);
    out.writeInt(offsetZ);
    out.writeInt(sizeZ);

    // Write every emitter position
    out.writeInt(emitterPositions.size());
    for(EmitterPosition pos : emitterPositions) {
      out.writeInt(pos.x);
      out.writeInt(pos.y);
      out.writeInt(pos.z);
    }

    // Write, for each cell, how many emitters are contained and their indexes in the array written earlier
    for(int i = 0; i < sizeX*sizeY*sizeZ; ++i) {
      int start = constructedGrid[2*i];
      int size = constructedGrid[2*i+1];
      out.writeInt(size);
      for(int j = 0; j < size; ++j) {
        out.writeInt(positionIndexes[start+j]);
      }
    }
  }

  /**
   * Load the grid from the given input stream
   * @param in The input stream to read the grid from
   * @return The grid
   */
  public static Grid load(DataInputStream in) throws IOException {
    int version = in.readInt();
    if(version != GRID_FORMAT_VERSION) {
      throw new RuntimeException("Unknown grid format version, can't load the grid");
    }

    int cellSize = in.readInt();
    Grid grid = new Grid(cellSize);
    grid.offsetX = in.readInt();
    grid.sizeX = in.readInt();
    grid.offsetY = in.readInt();
    grid.sizeY = in.readInt();
    grid.offsetZ = in.readInt();
    grid.sizeZ = in.readInt();

    // Read emitter positions
    int emitterNo = in.readInt();
    grid.emitterPositions = new ArrayList<>(emitterNo);
    for(int i = 0; i < emitterNo; ++i) {
      int x = in.readInt();
      int y = in.readInt();
      int z = in.readInt();
      grid.emitterPositions.add(new EmitterPosition(x, y, z));
    }

    int cellCount = grid.sizeX*grid.sizeY*grid.sizeZ;
    ArrayList<Integer> positionIndexesList = new ArrayList<>();
    int constructedGridCurrentIndex = 0;
    grid.constructedGrid = new int[cellCount*2];

    for(int cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
      int numberOfIndexes = in.readInt();
      for(int posIndex = 0; posIndex < numberOfIndexes; ++posIndex) {
        int index = in.readInt();
        positionIndexesList.add(index);
      }
      grid.constructedGrid[2*cellIndex] = constructedGridCurrentIndex;
      grid.constructedGrid[2*cellIndex + 1] = numberOfIndexes;
      constructedGridCurrentIndex += numberOfIndexes;
    }

    grid.positionIndexes = new int[positionIndexesList.size()];
    for(int i = 0; i < positionIndexesList.size(); ++i)
      grid.positionIndexes[i] = positionIndexesList.get(i);

    return grid;
  }
}
