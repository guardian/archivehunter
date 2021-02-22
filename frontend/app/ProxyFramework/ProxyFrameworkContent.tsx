import React from "react"
import {ColDef} from "@material-ui/data-grid";
import {IconButton} from "@material-ui/core";
import {DeleteForever} from "@material-ui/icons";
import {ProxyFrameworkDeploymentRow} from "../types";

function makeProxyFrameworkColumns(callDelete:(region:string)=>void):ColDef[] {
    return [
        {
            field: "region",
            headerName: "Region"
        },
        {
            field: "inputTopicArn",
            headerName: "Input Topic",
            width: 200
        },
        {
            field: "outputTopicArn",
            headerName: "Reply Topic",
            width: 200
        },
        {
            field: "roleArn",
            headerName: "Management Role",
            width: 200
        },
        {
            field: "subscriptionId",
            headerName: "Subscription",
            width: 200
        },
        {
            field: "actions",
            headerName: " ",
            width: 50,
            renderCell: (params)=><span>
                <IconButton onClick={()=>callDelete((params.row as ProxyFrameworkDeploymentRow).region)}>
                    <DeleteForever/>
                </IconButton>
            </span>
        }
    ]
}

export {makeProxyFrameworkColumns}