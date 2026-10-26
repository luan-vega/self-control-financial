package com.selfcontrol.app.importing;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Arquivo acima de spring.servlet.multipart.max-file-size: o erro acontece antes de chegar ao
 * controller, então é tratado aqui e volta como mensagem no bloco do resultado.
 */
@ControllerAdvice(annotations = Controller.class)
class UploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(Model model) {
        model.addAttribute("error", "Arquivo grande demais. O limite é 5 MB; divida o extrato em períodos menores.");
        return ImportPageController.RESULT;
    }
}
