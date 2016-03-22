package osp.Threads;
import java.util.Vector;
import java.util.Enumeration;
import osp.Utilities.*;
import osp.IFLModules.*;
import osp.Tasks.*;
import osp.EventEngine.*;
import osp.Hardware.*;
import osp.Devices.*;
import osp.Memory.*;
import osp.Resources.*;

/**
 *   This class is responsible for actions related to threads, including
 *   creating, killing, dispatching, resuming, and suspending threads.
 *
 *   @OSPProject Threads
 */
public class ThreadCB extends IflThreadCB 
{
    private static String threadErrorInfo;
    private static String threadwarningInfo;
    private static int targetSwitchOutStatus;

    private static Vector<ThreadCB> readyToRunThreads;
    /**
    *       The thread constructor. Must call 
    *
    *       	   super();
    *
    *       as its first statement.
    *
    *       @OSPProject Threads
    */
    public ThreadCB()
    {
        super();
    }

    /**
    *   This method will be called once at the beginning of the
    *   simulation. The student can set up static variables here.
    *   
    *   @OSPProject Threads
    */
    public static void init()
    {
        atLog(null, "Initialize ThreadCB static features");
        readyToRunThreads = new Vector<ThreadCB>();
        targetSwitchOutStatus = GlobalVariables.ThreadReady;
        threadErrorInfo = "null";
        threadwarningInfo = "null";
    }

    /** 
    *   Sets up a new thread and adds it to the given task. 
    *   The method must set the ready status 
    *   and attempt to add thread to task. If the latter fails 
    *   because there are already too many threads in this task, 
    *   so does this method, otherwise, the thread is appended 
    *   to the ready queue and dispatch() is called.
    *
	*   The priority of the thread can be set using the getPriority/setPriority
    *	methods. However, OSP itself doesn't care what the actual value of
    *	the priority is. These methods are just provided in case priority
    *	scheduling is required.
    *
	*@return thread or null
    *
    *        @OSPProject Threads
    */
    static public ThreadCB do_create(TaskCB task)
    {
        atLog(task, "Create thread");
        //create thread
        if (task.getThreadCount() >= MaxThreadsPerTask) {
            threadwarningInfo = "Max thread count exceeded.";
            ThreadCB.atWarning();
            dispatch();
            return null;
        }
        ThreadCB thread = new ThreadCB();

        //associate thread with task
        if (task.addThread(thread) == GlobalVariables.FAILURE){
            threadErrorInfo = "Fail to add thread to task";
            ThreadCB.atWarning();
            dispatch();
            return null;
        } else thread.setTask(task);

        ///priority not implemented
        // thread.setPriority(task.getPriority())

        //set status to ready to run
        thread.setStatus(GlobalVariables.ThreadReady);

        //add threads to ready to run list
        readyToRunThreads.add(thread);

        //Regardless of whether the new thread was created successfully, 
        // the dispatcher must be called or else a warning will be issued
        ///not implemented
        dispatch();
        return thread;
    }


    /** 
    *	Kills the specified thread. 
    *
    *	The status must be set to ThreadKill, the thread must be
    *	removed from the task's list of threads and its pending IORBs
    *	must be purged from all device queues.
    *        
    *	If some thread was on the ready queue, it must removed, if the 
    *	thread was running, the processor becomes idle, and dispatch() 
    *	must be called to resume a waiting thread.
    *	
	* @OSPProject Threads
    */
    public void do_kill()
    {
        atLog(this, "Thread kill");

        int currentStatus = getStatus();

        //Set status to ThreadKill
        setStatus(GlobalVariables.ThreadKill);

        //For ready state thread
        if (currentStatus == GlobalVariables.ThreadReady) {
            //Remove from ready queue
            readyToRunThreads.remove(this);
        }

        //For running state thread
        if (currentStatus == GlobalVariables.ThreadRunning) {
            //Context Switch: remove from CPU, and dispatch new thread
            // ThreadCB.do_preemption(GlobalVariables.ThreadKill);
            targetSwitchOutStatus = GlobalVariables.ThreadKill;
            ThreadCB.dispatch();
            targetSwitchOutStatus = GlobalVariables.ThreadReady;
        }

        //Remove thread from task
        TaskCB task = getTask();
        task.removeThread(this);

        //Cancelling devices request
        for (int id = 0; id < Device.getTableSize(); id ++) {
            Device.get(id).cancelPendingIO(this);
        }

        //Release resources
        ResourceCB.giveupResources(this);

        //Kill the task if no thread left
        if (getTask().getThreadCount() == 0) {
            getTask().kill();
        }

    }

    /** Suspends the thread that is currenly on the processor on the 
    *   specified event. 
    *
    *   Note that the thread being suspended doesn't need to be
    *   running. It can also be waiting for completion of a pagefault
    *   and be suspended on the IORB that is bringing the page in.
	*
    *	Thread's status must be changed to ThreadWaiting or higher,
    *   the processor set to idle, the thread must be in the right
    *   waiting queue, and dispatch() must be called to give CPU
    *   control to some other thread.
    *
    *	@param event - event on which to suspend this thread.
    *
    *        @OSPProject Threads
    *    */
    public void do_suspend(Event event)
    {
        atLog(event, "Thread suspended by event");

        int currentStatus = getStatus();

        //Place the thread to a certain event queue
        event.addThread(this);

        //If suspend a running thread, context switch
        if (currentStatus == GlobalVariables.ThreadRunning) {
            //Context switch
            targetSwitchOutStatus = ThreadWaiting;
            ThreadCB.dispatch();
            targetSwitchOutStatus = ThreadReady;
        } 
        //If suspend a waiting thread, increase waiting level
        else if (currentStatus >= GlobalVariables.ThreadWaiting) {
            setStatus(currentStatus + 1);
        }

    }

    /** Resumes the thread.
    *
    *	Only a thread with the status ThreadWaiting or higher
    *	can be resumed.  The status must be set to ThreadReady or
    *	decremented, respectively.
    *	A ready thread should be placed on the ready queue.
	*
    *	@OSPProject Threads
    */
    public void do_resume()
    {
        atLog(this, "Thread resume.");

        int currentStatus = getStatus();

        //Change to ready if waiting level 0
        if (currentStatus == GlobalVariables.ThreadWaiting) {
            setStatus(GlobalVariables.ThreadReady);
            readyToRunThreads.add(this);
        }
        //Lower the level of waiting if level is larger than 0
        else if (currentStatus > GlobalVariables.ThreadWaiting) {
            setStatus(currentStatus - 1);
        }
        //Other status cannot do resume
        else {
            threadwarningInfo = "Attempt to resume " + this + ", which wasn't waiting";
            ThreadCB.atWarning();
        }

    }

    /** 
    *   Selects a thread from the ready to run queue and dispatches it. 
    *
    *   If there is just one thread ready to run, reschedule the thread 
    *   currently on the processor.
    *
    *   In addition to setting the correct thread status it must
    *   update the PTBR.
    *
    *   FIFO scheduling: most context switch happens on process termination 
    *   no time slicing interruption. Overhead is minimal, but throughput
    *   can be low
	*
    *	@return SUCCESS or FAILURE
    *
    *        @OSPProject Threads
    */
    public static int do_dispatch()
    {
        atLog(null, "Dispatch new thread.");

        //Preemption
        do_preemption();

        //Dispatching
        //Error when no thread in ready queue
        if (readyToRunThreads.size() == 0) {
            threadErrorInfo = "no ready to run thread when dispatching.";
            ThreadCB.atError();
            return GlobalVariables.FAILURE;
        }

        //Select a thread: FIFO
        ThreadCB selectedThread = readyToRunThreads.remove(0);
        //Reschedule the same thread when it is the only thread
        if (readyToRunThreads.size() == 0) {
            readyToRunThreads.add(selectedThread);
        }

        //Set status to Thread Running
        selectedThread.setStatus(GlobalVariables.ThreadRunning);
        //Set page table and current thread 
        TaskCB task = selectedThread.getTask();
        MMU.setPTBR(task.getPageTable());
        task.setCurrentThread(selectedThread);

        //Context switch
        return GlobalVariables.SUCCESS;
    }

    /**
    *   The context switch.
    * 
    *   Control of the CPU of the current thread is preempted and another 
    *   thread will be dispatched.
    *   @param newThread the selected thread to be dispatch.
    *   @param switchOutStatus the target status for the current thread 
    *   that is going to be switched out. It is decided by the caller, but
    *   must be either ThreadWaiting or ThreadReady
    */
    private static void do_preemption()
    {
        atLog(null, "Preempt thread.");

        //Get current running thread
        PageTable pt = MMU.getPTBR();
        ThreadCB currentThread = pt.getTask().getCurrentThread();
        
        //Current thread must be running in CPU
        if (currentThread.getStatus() == GlobalVariables.ThreadRunning) {
            currentThread.setStatus(targetSwitchOutStatus);
        } else {
            threadErrorInfo = "Errorness status of current running thread";
            ThreadCB.atError();
        }

        //Set the page table base register
        MMU.setPTBR(null);

        //Set current running thread to null
        pt.getTask().setCurrentThread(null);
    }

    private static String signature = "<XXL> ";
    /**
    *   Called by OSP after printing an error message. The student can
    *   insert code here to print various tables and data structures in
    *   their state just after the error happened.  The body can be
    *   left empty, if this feature is not used.
    *
    *       @OSPProject Threads
    */
    public static void atError()
    {
        MyOut.error(
            // MMU.getPTBR().getTask().getCurrentThread(), 
            threadErrorInfo,
            signature + threadErrorInfo);
    }

    /** Called by OSP after printing a warning message. The student
        can insert code here to print various tables and data
        structures in their state just after the warning happened.
        The body can be left empty, if this feature is not used.
       
        @OSPProject Threads
     */
    public static void atWarning()
    {
        MyOut.error(
            // MMU.getPTBR().getTask().getCurrentThread(), 
            threadwarningInfo,
            signature + threadwarningInfo);
    }

    public static void atLog(Object src, String msg)
    {
        if (src == null) {
            MyOut.print(msg, signature + msg);
        } else {
            MyOut.print(src, signature + msg);   
        }
    }

}
