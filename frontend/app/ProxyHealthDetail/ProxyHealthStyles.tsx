import React from "react";
import {createStyles} from "@material-ui/core";

const proxyHealthStyles = createStyles({
    chartContainerWide: {
        float: "left",
        width: "50vw",
        position: "relative",
        overflow: "hidden"
    },
    chartsBar: {
        height: "600px"
    },
    chartContainer: {
        maxWidth: "800px",
        minWidth: "500px"
    },
    listControlLabel: {
        marginBottom: 0
    },
    chartDivider: {
        marginTop: "1em",
        marginBottom: "0.5em"
    }
});

export {proxyHealthStyles};