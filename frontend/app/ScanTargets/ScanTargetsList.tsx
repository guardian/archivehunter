import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import {Button, Paper} from "@material-ui/core";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import axios from "axios";
import {DataGrid} from "@material-ui/data-grid";
import {scanTargetColumns} from "./ScanTargetsListContent.tsx";

const ScanTargetsList:React.FC<RouteComponentProps> = (props) => {
    const [loading, setLoading] = useState(false);
    const [currentActionCaption, setCurrentActionCaption] = useState("Loading...");
    const [lastError, setLastError] = useState<string|null>(null)
    const [scanTargets, setScanTargets] = useState<any[]>([]);

    useEffect(()=>{
        setLoading(true);

        const doLoad = async ()=> {
            try {
                const response = await axios.get("/api/scanTarget")
                setScanTargets(response.data.entries);
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

    return <>
        <BreadcrumbComponent path={props.location ? props.location.pathname : "/unknown"}/>
        <div id="right-button-holder" style={{float: "right"}}>
            <Button onClick={newButtonClicked}>New</Button>
        </div>
        <div>
            <LoadingThrobber show={loading} small={true} caption={currentActionCaption}/>
            <ErrorViewComponent error={lastError}/>
        </div>
        <Paper elevation={3}>
            <DataGrid columns={scanTargetColumns} rows={scanTargets} pageSize={5}/>
        </Paper>
        </>
}

export default ScanTargetsList;