import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import axios from "axios";
import {Redirect} from "react-router-dom";
import AdminContainer from "../admin/AdminContainer";
import LoadingThrobber from "../common/LoadingThrobber";
import {DataGrid} from "@material-ui/data-grid";
import {Button, Grid, makeStyles, Paper} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import {ProxyFrameworkDeploymentRow, ProxyFrameworkDeploymentsResponse} from "../types";
import {makeProxyFrameworkColumns} from "./ProxyFrameworkContent";

const useStyles = makeStyles(Object.assign({
    tableContainer: {
        marginTop: "1em",
        height: "80vh"
    }
}, baseStyles));

const ProxyFrameworkList:React.FC<RouteComponentProps> = (props) => {
    const [currentDeployments, setCurrentDeployments] = useState<ProxyFrameworkDeploymentRow[]>([]);
    const [loading, setLoading] = useState(false);
    const [lastError, setLastError] = useState<any|undefined>(undefined);

    const classes = useStyles();

    const loadData = async ()=>{
        try {
            const result = await axios.get<ProxyFrameworkDeploymentsResponse>("/api/proxyFramework/deployments");
            setLoading(false);
            setLastError(undefined);
            setCurrentDeployments(result.data.entries.map((entry, idx)=>Object.assign({id: idx.toString()}, entry)));
        } catch (err) {
            console.error("Could not load proxy deployments: ", err);
            setLoading(false);
            setLastError(err);
        }
    }

    useEffect(()=>{
        setLoading(true);
        loadData();
    }, []);

    const callDelete = async (region:string) => {
        try {
            const result = await axios.delete("/api/proxyFramework/deployments/" + region);
            return loadData();
        } catch (err) {
            console.error("Could not delete proxy deployment: ", err);
            setLoading(false);
            setLastError(err);
        }
    }

    const columns = makeProxyFrameworkColumns(callDelete);

    return <AdminContainer {...props}>
        <Grid container justify="flex-start">
            <Grid item>
                <LoadingThrobber show={loading} caption="Loading data..." small={true}/>
            </Grid>
            <Grid item style={{marginLeft: "auto"}}>
                <Button variant="contained" onClick={()=>props.history.push("/admin/proxyFramework/new")}>Add</Button>
            </Grid>
        </Grid>

        <Paper elevation={3} className={classes.tableContainer}>
            <DataGrid columns={columns} rows={currentDeployments}/>
        </Paper>
    </AdminContainer>
}

export default ProxyFrameworkList;