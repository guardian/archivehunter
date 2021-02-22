import React from "react";
import {CheckCircle, Clear} from "@material-ui/icons";

interface TickCrossIconProps {
    value: boolean;
}

const TickCrossIcon:React.FC<TickCrossIconProps> = (props)=>{
    return props.value ? <CheckCircle style={{color: "green"}}/> : <Clear style={{color: "red"}}/>;
}

export default TickCrossIcon;