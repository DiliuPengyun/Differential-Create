package com.diliupengyun.differential_create;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.CrushingWork;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;

public final class CrushingPairRegression {
    private static int checks;
    private static final List<TorsionSolver.Link> PAIR = List.of(TorsionSolver.Link.work(0, 1, 8));
    private static TorsionSolver.Body body(double inertia, double drive) { return new TorsionSolver.Body(inertia, drive, 0, 0, 0); }
    public static void run() {
        double[][] examples = {{64,-64,64}, {128,-64,96}, {64,0,32}, {64,64,0}, {128,64,32}};
        for (double[] x : examples) near(x[2], CrushingWork.speed(x[0], x[1]), 0, "feed coordinate");
        for (int n : new int[]{1,2,4,8,16,32,64})
            near(Math.max(1, Math.log(n)/Math.log(2)), CrushingWork.batchFactor(n), 1e-14, "logarithmic batch work");
        for (double a : new double[]{-128,-.01,0,.01,64,128}) for (double b : new double[]{-64,0,64})
            for (double duration : new double[]{1e-5,.001,.1,2}) {
                var bodies = List.of(body(.1,0), body(.2,0));
                var result = TorsionSolver.advance(bodies, PAIR, new double[]{a,b}, new double[1], duration, .01);
                double relative = a-b, stop = Math.abs(relative)/(8*(10+5));
                double time = Math.min(duration,stop), sign = Math.signum(relative);
                double expectedA = a-sign*80*time, expectedB = b+sign*40*time;
                double travel = Math.abs(relative)*time-60*time*time;
                near(expectedA,result.speed()[0],2e-8,"isolated pair exact endpoint A");
                near(expectedB,result.speed()[1],2e-8,"isolated pair exact endpoint B");
                near(.1*a+.2*b,.1*result.speed()[0]+.2*result.speed()[1],1e-9,"pair conserves momentum");
                near(travel,result.workTravel()[0],3e-8,"absolute relative travel stops exactly");
                double loss=.05*a*a+.1*b*b-.05*result.speed()[0]*result.speed()[0]-.1*result.speed()[1]*result.speed()[1];
                near(8*travel,loss,2e-7,"paired work equals kinetic loss");
            }
        for (double drive : new double[]{-20,-8,-4,0,4,8,20}) {
            var bodies = List.of(body(.1,drive),body(.1,-drive));
            var result=TorsionSolver.advance(bodies,PAIR,new double[2],new double[1],.001,.001);
            near(Math.copySign(Math.max(0,Math.abs(drive)-8)*.01,drive),result.speed()[0],1e-10,"static torque bound");
            near(Math.clamp(drive,-8,8),result.torque()[0],1e-9,"static paired reaction");
        }
        // A high common speed must not hide a tiny relative stop, nor count common rotation as work.
        var shared=List.of(TorsionSolver.Link.work(0,1,8),TorsionSolver.Link.work(1,2,8));
        var result=TorsionSolver.advance(List.of(body(.1,4),body(.1,0),body(.1,-4)),shared,
            new double[]{64,64,64},new double[2],.02,.001);
        for(double speed:result.speed()) near(64,speed,1e-9,"shared static contacts");
        for(double travel:result.workTravel()) near(0,travel,1e-10,"common mode grants no work");
        for (double budget : new double[]{1e-6,.005,.1}) {
            var solver=TorsionSolver.prepare(List.of(body(.1,0),body(.1,0)),PAIR,new double[]{2,0},new double[1]);
            var event=solver.advanceToEvent(.1,.03,List.of(TorsionSolver.EventLimit.work(0,1,0,budget)));
            double maximum=.00625;
            near(Math.min(budget,maximum),event.workTravel()[0]/2,2e-10,"relative event budget");
            if(budget<maximum) {
                var rest=TorsionSolver.advance(List.of(body(.1,0),body(.1,0)),List.of(),event.speed(),new double[0],.1-event.duration(),.03);
                near(event.speed()[0],rest.speed()[0],0,"completion unloads both shafts");
            }
        }
        // Disconnected component remapping and query order must preserve work coordinates.
        var solver=TorsionSolver.prepare(List.of(body(.1,0),body(.1,0),body(1,0)),PAIR,new double[]{4,0,10},new double[1]);
        var first=solver.advance(.1,.01); solver.advance(.01,.01); var again=solver.advance(.1,.01);
        near(first.workTravel()[0],again.workTravel()[0],0,"discarded probe isolation");
        near(1,first.angle()[2],1e-14,"independent component clock");
        elasticCoupling();
        System.out.println("Crushing pair regression: "+checks+" checks passed");
    }
    private static void elasticCoupling() {
        double inf=Double.POSITIVE_INFINITY, k=100000;
        var links=List.of(new TorsionSolver.Link(0,1,1,k,0,inf,inf),new TorsionSolver.Link(2,3,1,k,0,inf,inf),
            TorsionSolver.Link.work(1,2,8));
        for(double drive:new double[]{-40,-4,0,4,40}) for(double initial:new double[]{0,.01,16}) {
            var bodies=List.of(body(.2,drive),body(.1,0),body(.1,0),body(.2,-drive));
            double[] speed={initial,initial,-initial,-initial};
            var result=TorsionSolver.advance(bodies,links,speed,new double[3],.03,.0001);
            double energy=0;
            for(int i=0;i<4;i++) energy+=.5*bodies.get(i).inertia()*(speed[i]*speed[i]-result.speed()[i]*result.speed()[i])
                +bodies.get(i).drive()*result.angle()[i];
            for(int e=0;e<2;e++) energy-=.5*k*result.twist()[e]*result.twist()[e];
            near(8*result.workTravel()[2],energy,2e-7,"elastic oscillations and reversals preserve work balance");
            if(Math.abs(result.torque()[2])>8+1e-9) throw new AssertionError("work torque exceeded cap");
        }
        var triangle=List.of(TorsionSolver.Link.work(0,1,8),TorsionSolver.Link.work(1,2,8),TorsionSolver.Link.work(2,0,8));
        var result=TorsionSolver.advance(List.of(body(.1,4),body(.1,-2),body(.1,-2)),triangle,new double[3],new double[3],.1,.001);
        for(double speed:result.speed()) near(0,speed,1e-8,"redundant static contact loop");
    }
    private static void near(double expected,double actual,double tolerance,String label) {
        checks++; if(!Double.isFinite(actual)||Math.abs(expected-actual)>tolerance)
            throw new AssertionError(label+": expected "+expected+", got "+actual);
    }
    public static void main(String[] args) { run(); }
}
