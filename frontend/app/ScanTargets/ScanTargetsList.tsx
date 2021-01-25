import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import BreadcrumbComponent from "../common/BreadcrumbComponent";
import {Button, Grid, makeStyles, Paper} from "@material-ui/core";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import axios from "axios";
import {DataGrid} from "@material-ui/data-grid";
import {makeScanTargetColumns} from "./ScanTargetsListContent";
import {ScanTarget, ScanTargetResponse} from "../types";
import AdminContainer from "../admin/AdminContainer";
import { baseStyles } from "../BaseStyles";

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

    const classes = useStyles();

    useEffect(()=>{
        setLoading(true);

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
        doLoad();
    }, []);

    const newButtonClicked = () => {
        props.history.push('/admin/scanTargets/new');
    }

    const deletionCb:(targetName:string)=>void = (targetName)=> {
        console.log("Delete button clicked for ", targetName);
    }

    const scanTargetColumns = makeScanTargetColumns(deletionCb);

    return <>
            <AdminContainer {...props}>
            <Grid container justify="space-between">
                <Grid item>
                    <LoadingThrobber show={loading} small={true} caption={currentActionCaption}/>
                    <ErrorViewComponent error={lastError}/>
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