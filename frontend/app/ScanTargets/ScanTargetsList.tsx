import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import {Button, Grid, makeStyles, Paper, Snackbar} from "@material-ui/core";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import axios from "axios";
import {DataGrid} from "@material-ui/data-grid";
import {makeScanTargetColumns} from "./ScanTargetsListContent";
import {ScanTarget, ScanTargetResponse} from "../types";
import AdminContainer from "../admin/AdminContainer";
import { baseStyles } from "../BaseStyles";
import MuiAlert from "@material-ui/lab/Alert";
import {formatError} from "../common/ErrorViewComponent";

const useStyles = makeStyles(Object.assign({
    tableContainer: {
        marginTop: "1em",
        height: "80vh"
    }
}, baseStyles));

const ScanTargetsList:React.FC<RouteComponentProps> = (props) => {
    const [loading, setLoading] = useState(false);
    const [currentActionCaption, setCurrentActionCaption] = useState("Loading...");
    const [lastError, setLastError] = useState<string|null>(null)
    const [scanTargets, setScanTargets] = useState<ScanTarget[]>([]);
    const [showingAlert, setShowingAlert] = useState(false);

    const classes = useStyles();

    const doLoad = async ()=> {
        try {
            const response = await axios.get<ScanTargetResponse>("/api/scanTarget")
            setScanTargets(response.data.entries.map((entry, idx)=>Object.assign({}, entry, {id: idx})));
            setLoading(false);
        } catch (err) {
            console.error("Could not load in scan targets: ", err);
            setLoading(false);
            setLastError(err.toString());
        }
    }


    useEffect(()=>{
        setLoading(true);
        doLoad();
    }, []);

    const newButtonClicked = () => {
        props.history.push('/admin/scanTargets/new');
    }

    const deletionCb = async (targetName:string)=> {
        try {
            await axios.delete(`/api/scanTarget/${targetName}`);
            window.setTimeout(()=> {
                setScanTargets([]);
                setLoading(true);
                return doLoad();
            }, 500);
        } catch(err) {
            console.error("Could not delete scan target: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    const scanTargetColumns = makeScanTargetColumns(deletionCb);

    const closeAlert = ()=>setShowingAlert(false);

    return <>
            <AdminContainer {...props}>
                {lastError ?
                    <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
                        <MuiAlert onClose={closeAlert}>{lastError}</MuiAlert>
                    </Snackbar> : null
                }
            <Grid container justify="space-between">
                <Grid item>
                    <LoadingThrobber show={loading} small={true} caption={currentActionCaption}/>
                </Grid>
                <Grid item >
                    <Button variant="contained" onClick={newButtonClicked}>New</Button>
                </Grid>
            </Grid>
            <Paper elevation={3} className={classes.tableContainer}>
                <DataGrid columns={scanTargetColumns} rows={scanTargets} pageSize={5}/>
            </Paper>
        </AdminContainer>
        </>
}

export default ScanTargetsList;