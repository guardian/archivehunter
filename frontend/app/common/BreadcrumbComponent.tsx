import React from 'react';
import {Link as RouterLink} from 'react-router-dom';
import {baseStyles} from "../BaseStyles";
import {Link, makeStyles} from "@material-ui/core";
import {StylesMap} from "../types";

interface BreadcrumbComponentProps {
    path: string;
}

const useStyles = makeStyles((theme)=>Object.assign({
    breadcrumbContainer: {
        textTransform: "capitalize",
        marginBottom: "1em",
    },
    breadcrumb: {
        fontSize: "1.1rem",
        paddingLeft: "0.4em",
    }
} as StylesMap, baseStyles));

const BreadcrumbComponent:React.FC<BreadcrumbComponentProps> = (props) => {
    const classes = useStyles();

    /**
     * returns a string composed of the parts of the array up to the provided index
     * @param array array to act on
     * @param idx number of parts to join
     */
    const recombineParts = (array:string[],idx:number) => {
        return "/" + array.slice(0, idx).join("/");
    }

    const pathParts = props.path.split(/\/+/).filter(elem=>elem!=="");
    const pathPartsLength = pathParts.length;

    return <span className={classes.breadcrumbContainer}>
        {
            pathParts.map((part,idx)=>{
                return <span key={idx}>
                    <Link component={RouterLink} className={classes.breadcrumb} to={recombineParts(pathParts,idx+1)}>{part}</Link>
                    { idx<pathPartsLength ? ">" : ""  }
                </span>
            })
        }
    </span>
}

export default BreadcrumbComponent;