import React, {useState, useEffect} from "react";
import {Grid, LinearProgress, makeStyles, Paper, Radio, Theme, Typography} from "@material-ui/core";
import LoadingThrobber from "../../common/LoadingThrobber";
import {
    ProxyFrameworkDeploymentRow,
    ProxyFrameworkSearchResponse,
    ProxyFrameworkStackRow,
    RegionScanError
} from "../../types";
import {ColDef, DataGrid} from "@material-ui/data-grid";
import axios from "axios";

interface FindDeploymentComponentProps {
    deploymentSelected: (deploymentId:string)=>void;
    currentSelectedDeployment?: string;
}

const useStyles = makeStyles((theme:Theme)=>({
    rightAlignedGridbox: {
        marginLeft: "auto"
    },
    errorText: {
        color: theme.palette.error.dark
    },
    emphasis: {
        fontWeight: "bold"
    },
    tableContainer: {
        height: "50vh",
        overflow: "hidden"
    }
}))

function makeSearchTableColumns(
    getSelectedValue: ()=>string|undefined,
    radioSelectorChanged: (newValue:string)=>void
):ColDef[] {
    return [
        {
            field: "selector",
            headerName: "Select",
            renderCell: (params) => (
                <Radio checked={params.getValue("stackId")===getSelectedValue()}
                       onClick={()=>radioSelectorChanged(params.getValue("stackId") as string)}
                />
            )
        },
        {
            field: "stackName",
            headerName: "Stack Name",
            width: 300
        },
        {
            field: "templateDescription",
            headerName: "Description",
            width: 400
        },
        {
            field: "region",
            headerName: "Region",
            width: 200
        },
        {
            field: "status",
            headerName: "Status",
            width: 200
        },
        {
            field: "creationTime",
            headerName: "Created",
            width: 300
        }
    ]
}

const FindDeploymentComponent:React.FC<FindDeploymentComponentProps> = (props) => {
    const classes = useStyles();

    const [loading, setLoading] = useState(false);
    const [lastError, setLastError] = useState<any|undefined>(undefined);
    const [regionErrors, setRegionErrors] = useState<RegionScanError[]>([]);
    const [foundDeployments, setFoundDeployments] = useState<ProxyFrameworkStackRow[]>([]);

    const getSelectedValue = () => {
        return props.currentSelectedDeployment;
    }

    const radioSelectorChanged = (newValue:string) => {
        console.log("You selected the deployment ", newValue);
        props.deploymentSelected(newValue);
    }

    const searchTableColumns = makeSearchTableColumns(getSelectedValue, radioSelectorChanged);

    /**
     * load in the list of deployments at mount. If we unmount while the load is still in progress, cancel it.
     */
    useEffect(()=>{
        const cancelTokenSource = axios.CancelToken.source();

        const loadData = async ()=> {
            const cancelToken = cancelTokenSource.token;
            try {
                const result = await axios.get<ProxyFrameworkSearchResponse>("/api/proxyFramework/deploymentScan", {cancelToken: cancelToken});
                //the search response contains a list of lists; the outer list for each region and the inner for each deployment
                //in said region.  We need to flatten that down into a single list here.
                const finalDeployments = result.data.success.flat().map((entry,idx)=>Object.assign({id: idx}, entry));
                setFoundDeployments(finalDeployments);
                setRegionErrors(result.data.failure.map((entry)=>({region: entry[0],error: entry[1]})));
                setLoading(false);
            } catch(err) {
                console.error("Could not load in deployments: ", err);
                if(err.hasOwnProperty("message") && err.message==="cancelled") {
                    console.log("proxy framework search was aborted")
                } else {
                    setLoading(false);
                    setLastError(err);
                }
            }
        }

        setLoading(true);
        loadData();

        return ()=>{
            cancelTokenSource.cancel("cancelled");
        }
    }, []);

    return <div>
        <Grid container>
            <Grid item>
                <Typography variant="h5">Search for deployment</Typography>
            </Grid>
            {loading ?
            <Grid item className={classes.rightAlignedGridbox}>
                <LinearProgress/>
                <Typography style={{marginLeft:"1em"}}>Searching...</Typography>
            </Grid> : null }

        </Grid>
        <Paper elevation={3} className={classes.tableContainer}>
            <DataGrid columns={searchTableColumns} rows={foundDeployments}/>
        </Paper>
        {
            regionErrors.length>0 ? <div style={{display: regionErrors.length>0 ? "block" : "none"}}>
                <Typography variant="h6">The following regions failed: </Typography>
                <ul>
                    {
                        regionErrors.map(entry=>
                            <li key={entry.region}>
                                <Typography className={classes.emphasis}>{entry.region}</Typography>
                                <Typography className={classes.errorText}>{entry.error}</Typography>
                            </li>
                        )
                    }
                </ul>
            </div> : null
        }

    </div>
}

export default FindDeploymentComponent;