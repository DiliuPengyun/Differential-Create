package com.diliupengyun.differential_create;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry;
import com.diliupengyun.differential_create.kinetics.ContraptionGeometry.Cell;

/** Analytic silhouettes, sealed cavities, inertia conservation and direction symmetry. */
public final class ContraptionShadowRegression {
    private static int checks;
    private ContraptionShadowRegression() {}
    public static void run() {
        var single=ContraptionGeometry.measure(Set.of(new Cell(0,0,0)));
        for(int axis=0;axis<3;axis++) for(int gap:new int[]{2,9,100,10000}) {
            var far=new Cell(axis==0?gap:0,axis==1?gap:0,axis==2?gap:0);
            var shape=ContraptionGeometry.measure(Set.of(new Cell(0,0,0),far));
            near(1,shape.translationArea()[axis],0,"axial shadows span empty gaps");
            near(single.linearDrag(axis),shape.linearDrag(axis),1e-18,"hidden block does not add frontal drag");
            near(2*single.linearInertia(),shape.linearInertia(),1e-18,"shadowing does not delete mass");
            for(int broad=0;broad<3;broad++) if(broad!=axis) near(2,shape.translationArea()[broad],0,"separated cubes remain visible from the side");
        }
        var shell=new HashSet<Cell>();var solid=new HashSet<Cell>();
        for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) for(int z=-1;z<=1;z++) {
            var cell=new Cell(x,y,z);solid.add(cell);if(x!=0||y!=0||z!=0)shell.add(cell);
        }
        var hollow=ContraptionGeometry.measure(shell);var filled=ContraptionGeometry.measure(solid);
        for(int axis=0;axis<3;axis++) {
            near(9,hollow.translationArea()[axis],0,"sealed cavity contributes no extra projected area");
            near(filled.rotationDrag()[axis],hollow.rotationDrag()[axis],1e-17,"sealed inner faces contribute no rotational air torque");
            near(ContraptionGeometry.CUBE_INERTIA,filled.rotationInertia()[axis]-hollow.rotationInertia()[axis],1e-16,"only the missing centre cube changes inertia");
        }
        for(double[] wind:new double[][]{{1,2,3},{-1,2,3},{3,-2,1},{.01,-8,16}})
            near(filled.translatedDrag(wind[0],wind[1],wind[2]),hollow.translatedDrag(wind[0],wind[1],wind[2]),1e-15,"sealed shell has the same oblique silhouette as the filled box");
        shell.remove(new Cell(0,0,-1));shell.remove(new Cell(0,0,1));
        near(8,ContraptionGeometry.measure(shell).translationArea()[2],0,"a genuine through-hole stays open");
        var diagonal=ContraptionGeometry.measure(Set.of(new Cell(0,0,0),new Cell(2,2,0)));
        near(single.translatedDrag(1,1,0),diagonal.translatedDrag(1,1,0),1e-18,"oblique wind sees one of two aligned diagonal cubes");
        var partial=ContraptionGeometry.measure(Set.of(new Cell(0,0,0),new Cell(2,1,0)));
        near(6*ContraptionGeometry.AIR_COEFFICIENT,partial.translatedDrag(1,1,0),1e-18,"half-overlapping oblique silhouettes expose one and a half cubes");
        var shapes=List.of(diagonal,partial,hollow,ContraptionGeometry.measure(Set.of(new Cell(-3,0,2),new Cell(-1,1,2),new Cell(2,-1,-3))));
        for(var shape:shapes) for(double[] wind:new double[][]{{1,1,0},{3,4,2},{-2,3,0},{0,0,1},{1,0,0}}) {
            double drag=shape.translatedDrag(wind[0],wind[1],wind[2]);
            near(drag,shape.translatedDrag(-wind[0],-wind[1],-wind[2]),1e-15,"reverse wind has equal opposing air loss");
            near(8*drag,shape.translatedDrag(2*wind[0],2*wind[1],2*wind[2]),1e-15,"air torque retains cubic mechanical folding");
            check(Double.isFinite(drag)&&drag>=0,"shaded drag is finite and passive");
        }
        var asymmetric=Set.of(new Cell(-3,1,2),new Cell(2,0,1),new Cell(1,2,-2),new Cell(-1,-2,0));
        var original=ContraptionGeometry.measure(asymmetric);
        for(int shift=0;shift<3;shift++) {
            var rotated=new HashSet<Cell>();
            for(var cell:asymmetric) {
                int[] p={cell.x(),cell.y(),cell.z()};rotated.add(new Cell(p[shift],p[(shift+1)%3],p[(shift+2)%3]));
            }
            var shape=ContraptionGeometry.measure(rotated);
            for(int axis=0;axis<3;axis++) near(original.rotationDrag()[(shift+axis)%3],shape.rotationDrag()[axis],1e-17,"cyclic axis permutations preserve ray-traced rotational torque");
        }
        near(0,ContraptionGeometry.measure(Set.of()).translatedDrag(1,2,3),0,"empty structure has no air drag");
        System.out.println("Contraption shadow regression: "+checks+" checks passed");
    }
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,double actual,double tolerance,String message) {
        check(Double.isFinite(actual)&&Math.abs(expected-actual)<=tolerance,message+": expected "+expected+", got "+actual);
    }
}
