import React, {useState} from "react";
import {RouteComponentProps} from "react-router";
import {
    Button,
    CircularProgress,
    createStyles,
    Divider,
    Grid,
    makeStyles, Snackbar,
    Step,
    StepLabel,
    Stepper,
    Theme
} from "@material-ui/core";
import AdminContainer from "../admin/AdminContainer";
import InitiateAddComponent from "./multistep/InitiateAddComponent";
import FindDeploymentComponent from "./multistep/FindDeploymentComponent";
import ConfirmationComponent from "./multistep/ConfirmationComponent";
import EnterDeploymentComponent from "./multistep/EnterDeploymentComponent";
import axios from "axios";
import MuiAlert from "@material-ui/lab/Alert";
import {ProxyFrameworkAutoConnection, ProxyFrameworkManualConnection} from "../types";
import ErrorViewComponent from "../common/ErrorViewComponent";

const useStyles = makeStyles((theme: Theme) =>
    createStyles({
        root: {
            width: '100%',
        },
        button: {
            marginRight: theme.spacing(1),
        },
        instructions: {
            marginTop: theme.spacing(1),
            marginBottom: theme.spacing(1),
        },
        buttonContainer: {
            width: "100%",
            marginLeft: "auto",
            marginRight: "auto"
        },
        controlsDivider: {
            marginTop: "1em",
            marginBottom: "0.5em"
        },
        stepper: {
            marginBottom: "1em"
        },
        throbber: {
            marginRight: theme.spacing(3),
            verticalAlign: "bottom"
        }
    }),
);

const steps = [
    "Search",
    "Select Deployment",
    "Confirm"
];

const stackIdRegex = /^\w+:\w+:cloudformation:([\w\d\-]+):\d+:stack\/([^/]+)\/(.*)$/;

function breakdownStackId(stackId:string):ProxyFrameworkAutoConnection|null{
    const results = stackIdRegex.exec(stackId);
    if(!results){
        console.error("Stack id ", stackId, " is not valid");
        return null;
    } else {
        return {region: results[1], stackName: results[2], uuid: results[3]}
    }
}

const ProxyFrameworkAdd:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();

    const [activeStep, setActiveStep] = useState(0);
    const [searchMode, setSearchMode] = useState<"search"|"entry">("search");
    const [selectedDeployment, setSelectedDeployment] = useState<string|undefined>(undefined);
    const [manualInput, setManualInput] = useState<ProxyFrameworkManualConnection|undefined>(undefined);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [connectionInProgress, setConnectionInProgress] = useState(false);
    const [showingAlert, setShowingAlert] = useState(false);

    const componentErrorOccurrec = (description:string)=>{
        setLastError(description);
        setShowingAlert(true);
    }

    /**
     * returns the component for this step.  Returns null if the step is out of range
     */
    const contentBody = () => {
        switch(activeStep) {
            case 0:
                return <InitiateAddComponent searchMode={searchMode} searchModeUpdated={(newMode)=>setSearchMode(newMode)}/>;
            case 1:
                return searchMode=="search" ? <FindDeploymentComponent
                                                errorOccurred={componentErrorOccurrec}
                                                deploymentSelected={(dpl:any)=>setSelectedDeployment(dpl)}
                                                currentSelectedDeployment={selectedDeployment}
                                                /> :
                    <EnterDeploymentComponent/>
            case 2:
                return <ConfirmationComponent searchMode={searchMode}
                                              selectedDeployment={selectedDeployment}
                                              manualInput={manualInput}
                                              />
            default:
                return null;
        }
    }

    const maxSteps = steps.length;

    const connectExistingStack = async ()=>{
        const stackInfo = selectedDeployment ? breakdownStackId(selectedDeployment) : null;

        if(stackInfo==null) {
            setLastError("The given stack ID is not valid")
            setShowingAlert(true);
        } else {
            const request = {
                region: stackInfo.region,
                stackName: stackInfo.stackName
            };

            setConnectionInProgress(true);
            try {
                const result = await axios.post("/api/proxyFramework/deployments", request)
                setConnectionInProgress(false);
                props.history.push("/admin/proxyFramework")
            } catch(err) {
                console.error(err);
                setLastError(ErrorViewComponent.formatError(err, false));
                setShowingAlert(true);
                setConnectionInProgress(false);
            }
        }
    }

    const connectManual = async () => {

    }

    const performConnection = ()=>{
        searchMode==="search" ? connectExistingStack() : connectManual();
    }

    const closeAlert = ()=>setShowingAlert(false);

    return <AdminContainer {...props}>
        <>
            <Snackbar open={showingAlert} autoHideDuration={8000} onClose={closeAlert}>
                <MuiAlert elevation={6} severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
            </Snackbar>
        <Stepper activeStep={activeStep} className={classes.stepper}>
            {
                steps.map((label, idx)=>(
                    <Step key={label} completed={activeStep>idx}>
                        <StepLabel>{label}</StepLabel>
                    </Step>
                ))
            }
        </Stepper>
            {
                contentBody()
            }
            <Divider className={classes.controlsDivider}/>
            <Grid container className={classes.buttonContainer} justify="space-between">
                <Grid item>
                    <Button onClick={()=>setActiveStep(activeStep-1)} disabled={activeStep<1 || connectionInProgress} variant="outlined">Back</Button>
                </Grid>
                <Grid item>
                    {
                        activeStep<maxSteps-1 ?
                            <Button onClick={()=>setActiveStep(activeStep+1)} disabled={activeStep>=maxSteps-1} variant="contained">Next</Button> :
                            <>
                                {
                                    connectionInProgress ? <CircularProgress className={classes.throbber}/> : null
                                }
                                <Button onClick={()=>performConnection()} disabled={connectionInProgress} variant="contained">Confirm</Button>
                            </>
                    }

                </Grid>
            </Grid>
    </>
    </AdminContainer>
}

export default ProxyFrameworkAdd;